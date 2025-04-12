package me.synapsed.aws.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.RequestIdentity;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public class PeerConnectionHandlerTest {
    private static final String TEST_DID = "test-did";
    private static final String TEST_PROOF = "test-proof";
    private static final String TEST_PEER_ID = "test-peer-id";
    private static final String TEST_SOURCE_IP = "127.0.0.1";
    
    private DynamoDbClient dynamoDbClient;
    private PeerConnectionHandler handler;
    private Context context;
    private LambdaLogger logger;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Set required environment variables
        System.setProperty("PEER_CONNECTIONS_TABLE", "test-peer-connections-table");
        System.setProperty("SUBSCRIPTION_PROOFS_TABLE", "test-subscription-proofs-table");
        
        dynamoDbClient = mock(DynamoDbClient.class);
        handler = new PeerConnectionHandler(dynamoDbClient);
        context = mock(Context.class);
        logger = mock(LambdaLogger.class);
        when(context.getLogger()).thenReturn(logger);
        objectMapper = new ObjectMapper();
    }

    private APIGatewayProxyRequestEvent createRequest(Map<String, String> headers, Map<String, String> pathParams) {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setHeaders(headers);
        request.setPathParameters(pathParams);
        
        // Set up request context with identity
        ProxyRequestContext requestContext = new ProxyRequestContext();
        RequestIdentity identity = new RequestIdentity();
        identity.setSourceIp(TEST_SOURCE_IP);
        requestContext.setIdentity(identity);
        request.setRequestContext(requestContext);
        
        return request;
    }

    @Test
    void testHandleRequestMissingHeaders() {
        APIGatewayProxyRequestEvent request = createRequest(null, null);
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(400, response.getStatusCode());
        assertEquals("Missing request headers", response.getBody());
    }

    @Test
    void testHandleRequestMissingSubscriptionProof() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        
        APIGatewayProxyRequestEvent request = createRequest(headers, null);
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(400, response.getStatusCode());
        assertEquals("Missing X-Subscription-Proof header", response.getBody());
    }

    @Test
    void testHandleRequestMissingActionParameter() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        // Create request with valid headers but missing action parameter
        APIGatewayProxyRequestEvent request = createRequest(headers, new HashMap<>());
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(400, response.getStatusCode());
        assertEquals("Missing action parameter", response.getBody());
    }

    @Test
    void testHandleRequestInvalidActionParameter() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "invalid-action");
        
        // Create request with valid headers but invalid action parameter
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(400, response.getStatusCode());
        assertEquals("Invalid action: invalid-action", response.getBody());
    }

    @Test
    void testHandleRequestInvalidSubscriptionProof() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "connect");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return false
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(403, response.getStatusCode());
        assertEquals("Invalid or expired subscription proof", response.getBody());
    }

    @Test
    void testHandleConnectSuccess() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "connect");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return no existing connections
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.emptyList()).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(200, response.getStatusCode());
        
        Map<String, String> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, String>>() {});
        assertTrue(responseBody.containsKey("peerId"));
        assertEquals("connected", responseBody.get("status"));
    }

    @Test
    void testHandleConnectExistingConnection() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "connect");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return an existing connection
        Map<String, AttributeValue> existingConnection = new HashMap<>();
        existingConnection.put("peerId", AttributeValue.builder().s(TEST_PEER_ID).build());
        existingConnection.put("did", AttributeValue.builder().s(TEST_DID).build());
        existingConnection.put("endpoint", AttributeValue.builder().s(TEST_SOURCE_IP).build());
        existingConnection.put("connectionId", AttributeValue.builder().s("test-connection-id").build());
        existingConnection.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
        existingConnection.put("status", AttributeValue.builder().s("connected").build());
        
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.singletonList(existingConnection)).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(200, response.getStatusCode());
        
        Map<String, String> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, String>>() {});
        assertEquals(TEST_PEER_ID, responseBody.get("peerId"));
        assertEquals("reconnected", responseBody.get("status"));
    }

    @Test
    void testHandleDisconnectSuccess() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "disconnect");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return an existing connection
        Map<String, AttributeValue> existingConnection = new HashMap<>();
        existingConnection.put("peerId", AttributeValue.builder().s(TEST_PEER_ID).build());
        existingConnection.put("did", AttributeValue.builder().s(TEST_DID).build());
        existingConnection.put("endpoint", AttributeValue.builder().s(TEST_SOURCE_IP).build());
        existingConnection.put("connectionId", AttributeValue.builder().s("test-connection-id").build());
        existingConnection.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
        existingConnection.put("status", AttributeValue.builder().s("connected").build());
        
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.singletonList(existingConnection)).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(200, response.getStatusCode());
        assertEquals("Peer disconnected successfully", response.getBody());
    }

    @Test
    void testHandleDisconnectNoConnection() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "disconnect");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return no existing connections
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.emptyList()).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(404, response.getStatusCode());
        assertEquals("No active connection found for this DID", response.getBody());
    }

    @Test
    void testHandleStatusConnected() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "status");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return an active connection
        Map<String, AttributeValue> existingConnection = new HashMap<>();
        existingConnection.put("peerId", AttributeValue.builder().s(TEST_PEER_ID).build());
        existingConnection.put("did", AttributeValue.builder().s(TEST_DID).build());
        existingConnection.put("endpoint", AttributeValue.builder().s(TEST_SOURCE_IP).build());
        existingConnection.put("connectionId", AttributeValue.builder().s("test-connection-id").build());
        existingConnection.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
        existingConnection.put("status", AttributeValue.builder().s("connected").build());
        
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.singletonList(existingConnection)).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(200, response.getStatusCode());
        
        Map<String, String> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, String>>() {});
        assertEquals("connected", responseBody.get("status"));
        assertEquals(TEST_PEER_ID, responseBody.get("peerId"));
    }

    @Test
    void testHandleStatusTimeout() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "status");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return a timed out connection
        Map<String, AttributeValue> existingConnection = new HashMap<>();
        existingConnection.put("peerId", AttributeValue.builder().s(TEST_PEER_ID).build());
        existingConnection.put("did", AttributeValue.builder().s(TEST_DID).build());
        existingConnection.put("endpoint", AttributeValue.builder().s(TEST_SOURCE_IP).build());
        existingConnection.put("connectionId", AttributeValue.builder().s("test-connection-id").build());
        existingConnection.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() - 3600000)).build());
        existingConnection.put("status", AttributeValue.builder().s("connected").build());
        
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.singletonList(existingConnection)).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(200, response.getStatusCode());
        
        Map<String, String> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, String>>() {});
        assertEquals("timeout", responseBody.get("status"));
    }

    @Test
    void testHandleStatusDisconnected() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "status");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return no existing connections
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.emptyList()).build());
        
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        assertEquals(200, response.getStatusCode());
        
        Map<String, String> responseBody = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, String>>() {});
        assertEquals("disconnected", responseBody.get("status"));
    }

    @Test
    void testDynamoDBOperationsDuringConnectAndDisconnect() throws Exception {
        // Set up headers and path parameters for connect action
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> connectPathParams = new HashMap<>();
        connectPathParams.put("action", "connect");
        
        APIGatewayProxyRequestEvent connectRequest = createRequest(headers, connectPathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return no existing connections
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.emptyList()).build());
        
        // Execute connect action
        handler.handleRequest(connectRequest, context);
        
        // Verify that PutItem was called to create a new connection
        verify(dynamoDbClient).putItem(any(PutItemRequest.class));
        
        // Now test disconnect action
        Map<String, String> disconnectPathParams = new HashMap<>();
        disconnectPathParams.put("action", "disconnect");
        
        APIGatewayProxyRequestEvent disconnectRequest = createRequest(headers, disconnectPathParams);
        
        // Mock the peer connection query to return an existing connection
        Map<String, AttributeValue> existingConnection = new HashMap<>();
        existingConnection.put("peerId", AttributeValue.builder().s(TEST_PEER_ID).build());
        existingConnection.put("did", AttributeValue.builder().s(TEST_DID).build());
        existingConnection.put("endpoint", AttributeValue.builder().s(TEST_SOURCE_IP).build());
        existingConnection.put("connectionId", AttributeValue.builder().s("test-connection-id").build());
        existingConnection.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
        existingConnection.put("status", AttributeValue.builder().s("connected").build());
        
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.singletonList(existingConnection)).build());
        
        // Execute disconnect action
        handler.handleRequest(disconnectRequest, context);
        
        // Verify that DeleteItem was called to remove the connection
        verify(dynamoDbClient).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void testConnectionTimeoutCleanup() throws Exception {
        // Set up headers and path parameters for status action
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", TEST_DID);
        headers.put("X-Subscription-Proof", TEST_PROOF);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "status");
        
        APIGatewayProxyRequestEvent request = createRequest(headers, pathParams);
        
        // Mock the subscription proof verification to return true
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build());
        
        // Mock the peer connection query to return a timed out connection
        Map<String, AttributeValue> existingConnection = new HashMap<>();
        existingConnection.put("peerId", AttributeValue.builder().s(TEST_PEER_ID).build());
        existingConnection.put("did", AttributeValue.builder().s(TEST_DID).build());
        existingConnection.put("endpoint", AttributeValue.builder().s(TEST_SOURCE_IP).build());
        existingConnection.put("connectionId", AttributeValue.builder().s("test-connection-id").build());
        existingConnection.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() - 3600000)).build());
        existingConnection.put("status", AttributeValue.builder().s("connected").build());
        
        when(dynamoDbClient.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.singletonList(existingConnection)).build());
        
        // Execute status action
        handler.handleRequest(request, context);
        
        // Verify that DeleteItem was called to clean up the timed out connection
        verify(dynamoDbClient).deleteItem(any(DeleteItemRequest.class));
    }
} 
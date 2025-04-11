package me.synapsed.aws.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

@ExtendWith(MockitoExtension.class)
class PeerConnectionHandlerTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private Context context;

    @Mock
    private LambdaLogger logger;

    private PeerConnectionHandler handler;

    @BeforeEach
    void setUp() {
        // Set up environment variables
        System.setProperty("PEER_CONNECTIONS_TABLE", "test-peer-connections");
        
        handler = new PeerConnectionHandler(dynamoDbClient);
    }

    @Test
    void testHandleRequest_MissingDID() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setHeaders(new HashMap<>());
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "connect");
        request.setPathParameters(pathParams);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertEquals("Missing X-DID header", response.getBody());
    }

    @Test
    void testHandleRequest_MissingSubscriptionProof() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        request.setHeaders(headers);
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "connect");
        request.setPathParameters(pathParams);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertEquals("Missing X-Subscription-Proof header", response.getBody());
    }

    @Test
    void testHandleRequest_MissingAction() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "test-proof");
        request.setHeaders(headers);
        request.setPathParameters(new HashMap<>());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertEquals("Missing action parameter", response.getBody());
    }

    @Test
    void testHandleRequest_InvalidAction() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "test-proof");
        request.setHeaders(headers);
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "invalid");
        request.setPathParameters(pathParams);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid action"));
    }

    @Test
    void testHandleRequest_Connect_Success() {
        // Set up DynamoDB mock for this test
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "test-proof");
        request.setHeaders(headers);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "connect");
        request.setPathParameters(pathParams);
        
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        APIGatewayProxyRequestEvent.RequestIdentity identity = new APIGatewayProxyRequestEvent.RequestIdentity();
        identity.setSourceIp("127.0.0.1");
        requestContext.setIdentity(identity);
        request.setRequestContext(requestContext);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("peerId"));
        verify(dynamoDbClient).putItem(any(PutItemRequest.class));
    }

    @Test
    void testHandleRequest_Disconnect_Success() {
        // Set up DynamoDB mock for this test
        when(dynamoDbClient.deleteItem(any(DeleteItemRequest.class)))
            .thenReturn(DeleteItemResponse.builder().build());

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "test-proof");
        request.setHeaders(headers);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "disconnect");
        request.setPathParameters(pathParams);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertEquals("Peer disconnected successfully", response.getBody());
        verify(dynamoDbClient).deleteItem(any(DeleteItemRequest.class));
    }

    @Test
    void testHandleRequest_Connect_DynamoDBError() {
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenThrow(new RuntimeException("DynamoDB error"));

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "test-proof");
        request.setHeaders(headers);
        
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("action", "connect");
        request.setPathParameters(pathParams);
        
        APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = new APIGatewayProxyRequestEvent.ProxyRequestContext();
        APIGatewayProxyRequestEvent.RequestIdentity identity = new APIGatewayProxyRequestEvent.RequestIdentity();
        identity.setSourceIp("127.0.0.1");
        requestContext.setIdentity(identity);
        request.setRequestContext(requestContext);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(500, response.getStatusCode());
        assertTrue(response.getBody().contains("Error connecting peer"));
    }
} 
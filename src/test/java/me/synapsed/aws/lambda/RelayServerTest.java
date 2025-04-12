package me.synapsed.aws.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.lang.reflect.Field;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

class RelayServerTest {

    @Mock
    private Context context;

    @Mock
    private LambdaLogger logger;

    @Mock
    private DynamoDbClient dynamoDbClient;
    
    @Mock
    private SqsClient sqsClient;

    private RelayServer handler;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(context.getLogger()).thenReturn(logger);
        handler = new RelayServer();
        // Mock DynamoDB client
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(handler, dynamoDbClient);
        
        // Mock SQS client
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(handler, sqsClient);
        
        // Mock SQS response
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
            .thenReturn(SendMessageResponse.builder().messageId("test-message-id").build());
    }

    @Test
    void handleRequest_MissingDidHeader_Returns400() throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setHeaders(new HashMap<>());
        request.setBody("{}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Missing X-DID header"));
    }

    @Test
    void handleRequest_MissingProofHeader_Returns400() throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        request.setHeaders(headers);
        request.setBody("{}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Missing X-Subscription-Proof header"));
    }

    @Test
    void handleRequest_InvalidProof_Returns403() throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "invalid-proof");
        request.setHeaders(headers);
        request.setBody("{}");

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid or expired subscription proof"));
    }

    @Test
    void handleRequest_ValidProof_Returns200() throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "valid-proof");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"offer\",\"peerId\":\"peer-123\",\"sdp\":\"v=0\\r\\no=- 1234567890 2 IN IP4 127.0.0.1\\r\\ns=-\\r\\nt=0 0\\r\\na=group:BUNDLE audio video\\r\\n\"}");

        // Mock subscription proof lookup
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("did", AttributeValue.builder().s("test-did").build());
        proofItem.put("proof", AttributeValue.builder().s("valid-proof").build());
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());

        // Mock peer connection lookup
        Map<String, AttributeValue> peerItem = new HashMap<>();
        peerItem.put("peerId", AttributeValue.builder().s("peer-123").build());
        peerItem.put("endpoint", AttributeValue.builder().s("wss://example.com").build());
        peerItem.put("connectionId", AttributeValue.builder().s("conn-123").build());
        peerItem.put("status", AttributeValue.builder().s("connected").build());
        peerItem.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(
                GetItemResponse.builder().item(proofItem).build(),  // First call for subscription proof
                GetItemResponse.builder().item(peerItem).build()    // Second call for peer connection
            );

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Signaling message forwarded"));
    }

    @Test
    void handleRequest_InvalidSignalingType_Returns400() throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "valid-proof");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"invalid-type\",\"peerId\":\"peer-123\"}");

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("did", AttributeValue.builder().s("test-did").build());
        item.put("proof", AttributeValue.builder().s("valid-proof").build());
        item.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                .item(item)
                .build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid signaling type"));
    }
    
    @Test
    void handleRequest_MissingRequiredFields_Returns400() throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "valid-proof");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"offer\",\"peerId\":\"peer-123\"}"); // Missing sdp field

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("did", AttributeValue.builder().s("test-did").build());
        item.put("proof", AttributeValue.builder().s("valid-proof").build());
        item.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder()
                .item(item)
                .build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Missing required field for offer: sdp"));
    }
    
    @Test
    void handleRequest_PeerNotConnected_Returns400() throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        headers.put("X-Subscription-Proof", "valid-proof");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"offer\",\"peerId\":\"peer-123\",\"sdp\":\"v=0\\r\\no=- 1234567890 2 IN IP4 127.0.0.1\\r\\ns=-\\r\\nt=0 0\\r\\na=group:BUNDLE audio video\\r\\n\"}");

        // Mock subscription proof lookup
        Map<String, AttributeValue> proofItem = new HashMap<>();
        proofItem.put("did", AttributeValue.builder().s("test-did").build());
        proofItem.put("proof", AttributeValue.builder().s("valid-proof").build());
        proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());

        // Mock peer connection lookup with disconnected status
        Map<String, AttributeValue> peerItem = new HashMap<>();
        peerItem.put("peerId", AttributeValue.builder().s("peer-123").build());
        peerItem.put("endpoint", AttributeValue.builder().s("wss://example.com").build());
        peerItem.put("connectionId", AttributeValue.builder().s("conn-123").build());
        peerItem.put("status", AttributeValue.builder().s("disconnected").build());
        peerItem.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(
                GetItemResponse.builder().item(proofItem).build(),  // First call for subscription proof
                GetItemResponse.builder().item(peerItem).build()    // Second call for peer connection
            );

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Peer is not connected"));
    }
} 
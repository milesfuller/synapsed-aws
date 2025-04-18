package me.synapsed.aws.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
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
    private ObjectMapper objectMapper;

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
        
        // Initialize object mapper
        objectMapper = new ObjectMapper();
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
        
        // Verify the response contains ICE servers and direct connection attempt
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertTrue(responseBody.containsKey("iceServers"));
        assertTrue(responseBody.containsKey("attemptDirectConnection"));
        assertTrue((Boolean) responseBody.get("attemptDirectConnection"));
        
        // Verify the signaling message contains direct connection attempt
        verify(sqsClient).sendMessage(argThat(new ArgumentMatcher<SendMessageRequest>() {
            @Override
            public boolean matches(SendMessageRequest req) {
                try {
                    Map<String, Object> messageBody = objectMapper.readValue(req.messageBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    return messageBody.containsKey("attemptDirectConnection") && 
                           (Boolean) messageBody.get("attemptDirectConnection");
                } catch (Exception e) {
                    return false;
                }
            }
            
            @Override
            public String toString() {
                return "SendMessageRequest with attemptDirectConnection=true";
            }
        }));
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

    @Test
    void testMultiplePeersSignaling() throws Exception {
        // Setup three peers: initiator, responder1, and responder2
        String initiatorId = "peer1";
        String responder1Id = "peer2";
        String responder2Id = "peer3";
        
        // Mock subscription proofs and peer connections
        Map<String, AttributeValue> proofItem1 = createProofItem(initiatorId, "proof1");
        Map<String, AttributeValue> proofItem2 = createProofItem(responder1Id, "proof2");
        Map<String, AttributeValue> proofItem3 = createProofItem(responder2Id, "proof3");
        
        Map<String, AttributeValue> peerItem1 = createPeerItem(initiatorId, "conn1", "endpoint1", "connected");
        Map<String, AttributeValue> peerItem2 = createPeerItem(responder1Id, "conn2", "endpoint2", "connected");
        Map<String, AttributeValue> peerItem3 = createPeerItem(responder2Id, "conn3", "endpoint3", "connected");
        
        // Set up the chain of responses for all DynamoDB calls
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem1).build())
            .thenReturn(GetItemResponse.builder().item(peerItem1).build())
            .thenReturn(GetItemResponse.builder().item(proofItem2).build())
            .thenReturn(GetItemResponse.builder().item(peerItem2).build())
            .thenReturn(GetItemResponse.builder().item(proofItem3).build())
            .thenReturn(GetItemResponse.builder().item(peerItem3).build());
        
        // Mock SQS message sending
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
            .thenReturn(SendMessageResponse.builder().messageId("test-message-id").build());
        
        // Test 1: Initiator sends offer to responder1
        Map<String, Object> offerData = new HashMap<>();
        offerData.put("type", "offer");
        offerData.put("peerId", responder1Id);  // Target peer ID
        offerData.put("fromPeerId", initiatorId);  // Source peer ID
        offerData.put("sdp", "v=0\r\n...");
        
        APIGatewayProxyRequestEvent offerRequest = createRequest(offerData, initiatorId, "proof1");
        APIGatewayProxyResponseEvent offerResponse = handler.handleRequest(offerRequest, context);
        
        assertEquals(200, offerResponse.getStatusCode());
        Map<String, Object> offerResponseBody = objectMapper.readValue(offerResponse.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertTrue(offerResponseBody.containsKey("message"));
        assertEquals("Signaling message forwarded", offerResponseBody.get("message"));
        assertTrue(offerResponseBody.containsKey("attemptDirectConnection"));
        assertTrue((Boolean) offerResponseBody.get("attemptDirectConnection"));
        assertTrue(offerResponseBody.containsKey("iceServers"));
        
        // Test 2: Responder1 sends answer to initiator
        Map<String, Object> answerData = new HashMap<>();
        answerData.put("type", "answer");
        answerData.put("peerId", initiatorId);  // Target peer ID
        answerData.put("fromPeerId", responder1Id);  // Source peer ID
        answerData.put("sdp", "v=0\r\n...");
        
        APIGatewayProxyRequestEvent answerRequest = createRequest(answerData, responder1Id, "proof2");
        APIGatewayProxyResponseEvent answerResponse = handler.handleRequest(answerRequest, context);
        
        assertEquals(200, answerResponse.getStatusCode());
        assertEquals("Signaling message forwarded", answerResponse.getBody());
        
        // Test 3: Initiator sends ICE candidate to responder2
        Map<String, Object> iceData = new HashMap<>();
        iceData.put("type", "ice-candidate");
        iceData.put("peerId", responder2Id);  // Target peer ID
        iceData.put("fromPeerId", initiatorId);  // Source peer ID
        iceData.put("candidate", "candidate:1234567890 1 udp 2122260223 192.168.1.1 54321 typ host");
        
        APIGatewayProxyRequestEvent iceRequest = createRequest(iceData, initiatorId, "proof1");
        APIGatewayProxyResponseEvent iceResponse = handler.handleRequest(iceRequest, context);
        
        assertEquals(200, iceResponse.getStatusCode());
        assertEquals("Signaling message forwarded", iceResponse.getBody());
        
        // Verify SQS messages were sent
        verify(sqsClient, times(3)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void testPeerConnectionTimeout() throws Exception {
        String peerId = "peer1";
        String targetPeerId = "peer2";
        
        // Mock subscription proof and peer connection
        Map<String, AttributeValue> proofItem = createProofItem(peerId, "proof1");
        Map<String, AttributeValue> peerItem = createPeerItem(targetPeerId, "conn1", "endpoint1", "connected", System.currentTimeMillis() - (31 * 60 * 1000));
        
        // Set up the chain of responses
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build())
            .thenReturn(GetItemResponse.builder().item(peerItem).build());
        
        // Create signaling request
        Map<String, Object> offerData = new HashMap<>();
        offerData.put("type", "offer");
        offerData.put("peerId", targetPeerId);  // Target peer ID
        offerData.put("fromPeerId", peerId);  // Source peer ID
        offerData.put("sdp", "v=0\r\n...");
        
        APIGatewayProxyRequestEvent request = createRequest(offerData, peerId, "proof1");
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        
        assertEquals(400, response.getStatusCode());
        assertEquals("Peer connection has timed out", response.getBody());
        
        // Verify no SQS messages were sent
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void testInvalidPeerState() throws Exception {
        String peerId = "peer1";
        String targetPeerId = "peer2";
        
        // Mock subscription proof and peer connection
        Map<String, AttributeValue> proofItem = createProofItem(peerId, "proof1");
        Map<String, AttributeValue> peerItem = createPeerItem(targetPeerId, "conn1", "endpoint1", "disconnected");
        
        // Set up the chain of responses
        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(proofItem).build())
            .thenReturn(GetItemResponse.builder().item(peerItem).build());
        
        // Create signaling request
        Map<String, Object> offerData = new HashMap<>();
        offerData.put("type", "offer");
        offerData.put("peerId", targetPeerId);  // Target peer ID
        offerData.put("fromPeerId", peerId);  // Source peer ID
        offerData.put("sdp", "v=0\r\n...");
        
        APIGatewayProxyRequestEvent request = createRequest(offerData, peerId, "proof1");
        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);
        
        assertEquals(400, response.getStatusCode());
        assertEquals("Peer is not connected. Current status: disconnected", response.getBody());
        
        // Verify no SQS messages were sent
        verify(sqsClient, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void handleRequest_WithTurnServer_IncludesTurnServer() throws Exception {
        // Create environment variables with TURN server configuration
        Map<String, String> env = new HashMap<>();
        env.put("TURN_SERVER", "turn:test-turn-server.com:3478");
        env.put("TURN_USERNAME", "test-username");
        env.put("TURN_CREDENTIAL", "test-credential");
        
        // Create a new instance of RelayServer with the test environment
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks for the new handler
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

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
                GetItemResponse.builder().item(proofItem).build(),
                GetItemResponse.builder().item(peerItem).build()
            );

        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains TURN server configuration
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        boolean hasTurnServer = responseIceServers.stream()
            .anyMatch(server -> server.containsKey("username") && 
                              server.get("username").equals("test-username"));
        
        assertTrue(hasTurnServer, "Response should include TURN server configuration");
    }

    @Test
    void handleRequest_WithOnlyStunServer_IncludesStunServer() throws Exception {
        // Create environment variables with only STUN server configuration
        Map<String, String> env = new HashMap<>();
        env.put("STUN_SERVER", "stun:stun.test-server.com:19302");
        
        // Create a new instance of RelayServer with the test environment
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks for the new handler
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

        APIGatewayProxyRequestEvent request = createValidRequest();
        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains only STUN server configuration
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        assertEquals(1, responseIceServers.size(), "Should have exactly one ICE server");
        assertTrue(responseIceServers.get(0).containsKey("urls"), "Should have urls field");
        assertEquals("stun:stun.test-server.com:19302", responseIceServers.get(0).get("urls"), "Should have correct STUN server URL");
        assertFalse(responseIceServers.get(0).containsKey("username"), "Should not have username field for STUN");
        assertFalse(responseIceServers.get(0).containsKey("credential"), "Should not have credential field for STUN");
    }

    @Test
    void handleRequest_WithInvalidTurnCredentials_ExcludesTurnServer() throws Exception {
        // Create environment variables with invalid TURN credentials
        Map<String, String> env = new HashMap<>();
        env.put("STUN_SERVER", "stun:stun.test-server.com:19302");
        env.put("TURN_SERVER", "turn:test-turn-server.com:3478");
        env.put("TURN_USERNAME", ""); // Empty username
        env.put("TURN_CREDENTIAL", "test-credential");
        
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

        APIGatewayProxyRequestEvent request = createValidRequest();
        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains only STUN server
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        assertEquals(1, responseIceServers.size(), "Should have exactly one ICE server");
        assertTrue(responseIceServers.get(0).containsKey("urls"), "Should have urls field");
        assertEquals("stun:stun.test-server.com:19302", responseIceServers.get(0).get("urls"), "Should have correct STUN server URL");
    }

    @Test
    void handleRequest_WithMultipleIceServers_IncludesAllServers() throws Exception {
        // Create environment variables with multiple ICE servers
        Map<String, String> env = new HashMap<>();
        env.put("STUN_SERVER", "stun:stun1.test-server.com:19302,stun:stun2.test-server.com:19302");
        env.put("TURN_SERVER", "turn:turn1.test-server.com:3478,turn:turn2.test-server.com:3478");
        env.put("TURN_USERNAME", "test-username");
        env.put("TURN_CREDENTIAL", "test-credential");
        
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

        APIGatewayProxyRequestEvent request = createValidRequest();
        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains all ICE servers
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        assertEquals(4, responseIceServers.size(), "Should have all ICE servers");
        
        // Verify STUN servers
        assertTrue(responseIceServers.stream()
            .anyMatch(server -> server.get("urls").equals("stun:stun1.test-server.com:19302")));
        assertTrue(responseIceServers.stream()
            .anyMatch(server -> server.get("urls").equals("stun:stun2.test-server.com:19302")));
        
        // Verify TURN servers
        assertTrue(responseIceServers.stream()
            .anyMatch(server -> server.get("urls").equals("turn:turn1.test-server.com:3478") &&
                              server.get("username").equals("test-username")));
        assertTrue(responseIceServers.stream()
            .anyMatch(server -> server.get("urls").equals("turn:turn2.test-server.com:3478") &&
                              server.get("username").equals("test-username")));
    }

    @Test
    void handleRequest_WithMalformedStunUrl_DefaultsToGoogleStun() throws Exception {
        // Create environment variables with malformed STUN URL
        Map<String, String> env = new HashMap<>();
        env.put("STUN_SERVER", "invalid-stun-url");
        
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

        APIGatewayProxyRequestEvent request = createValidRequest();
        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains the default Google STUN server
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        assertEquals(1, responseIceServers.size(), "Should have exactly one ICE server");
        assertEquals("stun:stun.l.google.com:19302", responseIceServers.get(0).get("urls"), 
            "Should default to Google STUN server");
    }

    @Test
    void handleRequest_WithMalformedTurnUrl_ExcludesTurnServer() throws Exception {
        // Create environment variables with malformed TURN URL
        Map<String, String> env = new HashMap<>();
        env.put("STUN_SERVER", "stun:stun.test-server.com:19302");
        env.put("TURN_SERVER", "invalid-turn-url");
        env.put("TURN_USERNAME", "test-username");
        env.put("TURN_CREDENTIAL", "test-credential");
        
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

        APIGatewayProxyRequestEvent request = createValidRequest();
        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains only STUN server
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        assertEquals(1, responseIceServers.size(), "Should have exactly one ICE server");
        assertEquals("stun:stun.test-server.com:19302", responseIceServers.get(0).get("urls"), 
            "Should only include valid STUN server");
    }

    @Test
    void handleRequest_WithNoIceServers_ReturnsDefaultStun() throws Exception {
        // Create environment variables with no ICE servers
        Map<String, String> env = new HashMap<>();
        
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

        APIGatewayProxyRequestEvent request = createValidRequest();
        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains the default Google STUN server
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        assertEquals(1, responseIceServers.size(), "Should have exactly one ICE server");
        assertEquals("stun:stun.l.google.com:19302", responseIceServers.get(0).get("urls"), 
            "Should default to Google STUN server");
    }

    @Test
    void handleRequest_WithEmptyEnvironmentVariables_ReturnsDefaultStun() throws Exception {
        // Create environment variables with empty values
        Map<String, String> env = new HashMap<>();
        env.put("STUN_SERVER", "");
        env.put("TURN_SERVER", "");
        env.put("TURN_USERNAME", "");
        env.put("TURN_CREDENTIAL", "");
        
        RelayServer newHandler = new RelayServer(env);
        
        // Set up the mocks
        Field dynamoDbClientField = RelayServer.class.getDeclaredField("dynamoDbClient");
        dynamoDbClientField.setAccessible(true);
        dynamoDbClientField.set(newHandler, dynamoDbClient);
        
        Field sqsClientField = RelayServer.class.getDeclaredField("sqsClient");
        sqsClientField.setAccessible(true);
        sqsClientField.set(newHandler, sqsClient);

        APIGatewayProxyRequestEvent request = createValidRequest();
        APIGatewayProxyResponseEvent response = newHandler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        
        // Verify the response contains the default Google STUN server
        Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        List<Map<String, String>> responseIceServers = objectMapper.convertValue(responseBody.get("iceServers"), 
            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
        
        assertEquals(1, responseIceServers.size(), "Should have exactly one ICE server");
        assertEquals("stun:stun.l.google.com:19302", responseIceServers.get(0).get("urls"), 
            "Should default to Google STUN server");
    }

    private Map<String, AttributeValue> createProofItem(String did, String proof) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("did", AttributeValue.builder().s(did).build());
        item.put("proof", AttributeValue.builder().s(proof).build());
        item.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        return item;
    }

    private Map<String, AttributeValue> createPeerItem(String peerId, String connectionId, String endpoint, String status) {
        return createPeerItem(peerId, connectionId, endpoint, status, System.currentTimeMillis());
    }

    private Map<String, AttributeValue> createPeerItem(String peerId, String connectionId, String endpoint, String status, long connectedAt) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("peerId", AttributeValue.builder().s(peerId).build());
        item.put("connectionId", AttributeValue.builder().s(connectionId).build());
        item.put("endpoint", AttributeValue.builder().s(endpoint).build());
        item.put("status", AttributeValue.builder().s(status).build());
        item.put("connectedAt", AttributeValue.builder().s(String.valueOf(connectedAt)).build());
        return item;
    }

    private APIGatewayProxyRequestEvent createRequest(Map<String, Object> body, String did, String proof) throws Exception {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(body));
        
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", did);
        headers.put("X-Subscription-Proof", proof);
        request.setHeaders(headers);
        
        return request;
    }

    private APIGatewayProxyRequestEvent createValidRequest() throws Exception {
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
                GetItemResponse.builder().item(proofItem).build(),
                GetItemResponse.builder().item(peerItem).build()
            );

        return request;
    }
} 
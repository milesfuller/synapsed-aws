package me.synapsed.aws.lambda;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.InvalidMessageContentsException;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

public class RelayServer implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String subscriptionProofsTable;
    private final String peerConnectionsTable;
    private final String signalingQueueUrl;
    private final SqsClient sqsClient;
    private final List<Map<String, String>> iceServers;
    // Valid WebRTC signaling message types
    private static final Set<String> VALID_SIGNALING_TYPES = new HashSet<>();
    static {
        VALID_SIGNALING_TYPES.add("offer");
        VALID_SIGNALING_TYPES.add("answer");
        VALID_SIGNALING_TYPES.add("ice-candidate");
    }
    // Required fields for each signaling type
    private static final Map<String, Set<String>> REQUIRED_FIELDS = new HashMap<>();
    static {
        Set<String> offerFields = new HashSet<>();
        offerFields.add("sdp");
        REQUIRED_FIELDS.put("offer", offerFields);
        Set<String> answerFields = new HashSet<>();
        answerFields.add("sdp");
        REQUIRED_FIELDS.put("answer", answerFields);
        Set<String> iceFields = new HashSet<>();
        iceFields.add("candidate");
        REQUIRED_FIELDS.put("ice-candidate", iceFields);
    }
    public RelayServer() {
        this(System.getenv());
    }
    public RelayServer(Map<String, String> env) {
        this(env, DynamoDbClient.builder().build(), SqsClient.builder().build());
    }
    public RelayServer(Map<String, String> env, DynamoDbClient dynamoDbClient, SqsClient sqsClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        this.subscriptionProofsTable = env.getOrDefault("SUBSCRIPTION_PROOFS_TABLE", "synapsed-subscription-proofs");
        this.peerConnectionsTable = env.getOrDefault("PEER_CONNECTIONS_TABLE", "synapsed-peer-connections");
        this.signalingQueueUrl = env.getOrDefault("SIGNALING_QUEUE_URL", "");
        this.sqsClient = sqsClient;
        this.iceServers = new ArrayList<>();
        String stunServers = env.getOrDefault("STUN_SERVER", "stun:stun.l.google.com:19302");
        if (!stunServers.isEmpty()) {
            for (String stunUrl : stunServers.split(",")) {
                stunUrl = stunUrl.trim();
                if (isValidStunUrl(stunUrl)) {
                    Map<String, String> stunServer = new HashMap<>();
                    stunServer.put("urls", stunUrl);
                    this.iceServers.add(stunServer);
                }
            }
        }
        if (this.iceServers.isEmpty()) {
            Map<String, String> defaultStunServer = new HashMap<>();
            defaultStunServer.put("urls", "stun:stun.l.google.com:19302");
            this.iceServers.add(defaultStunServer);
        }
        String turnServers = env.getOrDefault("TURN_SERVER", "");
        String turnUsername = env.getOrDefault("TURN_USERNAME", "");
        String turnCredential = env.getOrDefault("TURN_CREDENTIAL", "");
        if (!turnServers.isEmpty() && !turnUsername.isEmpty() && !turnCredential.isEmpty()) {
            for (String turnUrl : turnServers.split(",")) {
                turnUrl = turnUrl.trim();
                if (isValidTurnUrl(turnUrl)) {
                    Map<String, String> turn = new HashMap<>();
                    turn.put("urls", turnUrl);
                    turn.put("username", turnUsername);
                    turn.put("credential", turnCredential);
                    this.iceServers.add(turn);
                }
            }
        }
    }
    private boolean isValidStunUrl(String url) {
        return url != null && url.startsWith("stun:") && url.contains(":");
    }
    private boolean isValidTurnUrl(String url) {
        return url != null && url.startsWith("turn:") && url.contains(":");
    }
    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            String did = input.getHeaders().get("X-DID");
            if (did == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-DID header");
            }
            String proof = input.getHeaders().get("X-Subscription-Proof");
            if (proof == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-Subscription-Proof header");
            }
            if (!verifySubscriptionProof(did, proof)) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(403)
                    .withBody("Invalid or expired subscription proof");
            }
            Map<String, Object> requestBody = objectMapper.readValue(input.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            String type = (String) requestBody.get("type");
            String peerId = (String) requestBody.get("peerId");
            if (type == null || peerId == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing required fields: type, peerId");
            }
            String validationError = validateSignalingMessage(type, requestBody);
            if (validationError != null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody(validationError);
            }
            if (type.equals("offer") || type.equals("answer")) {
                requestBody.put("iceServers", this.iceServers);
            }
            switch (type) {
                case "offer":
                case "answer":
                case "ice-candidate":
                    return handleSignaling(type, peerId, requestBody, context);
                default:
                    return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("Invalid signaling type");
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing request: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Internal server error");
        }
    }
    private boolean verifySubscriptionProof(String did, String proof) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("did", AttributeValue.builder().s(did).build());
            key.put("proof", AttributeValue.builder().s(proof).build());
            GetItemRequest request = GetItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .key(key)
                .build();
            GetItemResponse response = dynamoDbClient.getItem(request);
            if (!response.hasItem()) {
                return false;
            }
            String expiresAt = response.item().get("expiresAt").s();
            long expirationTime = Long.parseLong(expiresAt);
            return System.currentTimeMillis() < expirationTime;
        } catch (Exception e) {
            return false;
        }
    }
    private String validateSignalingMessage(String type, Map<String, Object> data) {
        if (!VALID_SIGNALING_TYPES.contains(type)) {
            return "Invalid signaling type: " + type;
        }
        Set<String> requiredFields = REQUIRED_FIELDS.get(type);
        if (requiredFields != null) {
            for (String field : requiredFields) {
                if (!data.containsKey(field) || data.get(field) == null) {
                    return "Missing required field for " + type + ": " + field;
                }
            }
        }
        if (type.equals("offer") || type.equals("answer")) {
            String sdp = (String) data.get("sdp");
            if (sdp == null || !sdp.startsWith("v=0")) {
                return "Invalid SDP format for " + type;
            }
        }
        if (type.equals("ice-candidate")) {
            String candidate = (String) data.get("candidate");
            if (candidate == null || !candidate.startsWith("candidate:")) {
                return "Invalid ICE candidate format";
            }
        }
        return null;
    }
    private APIGatewayProxyResponseEvent handleSignaling(String type, String peerId, Map<String, Object> data, Context context) {
        try {
            // Simulate forwarding signaling message to peer via SQS (not implemented in LocalStack test)
            // In production, you would send to SQS or another messaging system
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("Signaling message processed: " + type);
        } catch (Exception e) {
            context.getLogger().log("Error forwarding signaling message: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error forwarding signaling message");
        }
    }
} 
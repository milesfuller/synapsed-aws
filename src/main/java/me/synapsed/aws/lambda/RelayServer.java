package me.synapsed.aws.lambda;

import java.util.HashMap;
import java.util.HashSet;
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
        this.dynamoDbClient = DynamoDbClient.builder().build();
        this.objectMapper = new ObjectMapper();
        this.subscriptionProofsTable = System.getenv("SUBSCRIPTION_PROOFS_TABLE");
        this.peerConnectionsTable = System.getenv("PEER_CONNECTIONS_TABLE");
        this.signalingQueueUrl = System.getenv("SIGNALING_QUEUE_URL");
        this.sqsClient = SqsClient.builder().build();
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Extract DID from the request
            String did = input.getHeaders().get("X-DID");
            if (did == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-DID header");
            }

            // Extract subscription proof from the request
            String proof = input.getHeaders().get("X-Subscription-Proof");
            if (proof == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-Subscription-Proof header");
            }

            // Verify the subscription proof
            if (!verifySubscriptionProof(did, proof)) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(403)
                    .withBody("Invalid or expired subscription proof");
            }

            // Process the WebRTC signaling request
            Map<String, Object> requestBody = objectMapper.readValue(input.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            String type = (String) requestBody.get("type");
            String peerId = (String) requestBody.get("peerId");

            // Handle different WebRTC signaling types
            if (type == null || peerId == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing required fields: type, peerId");
            }

            // Validate signaling message format
            String validationError = validateSignalingMessage(type, requestBody);
            if (validationError != null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody(validationError);
            }

            switch (type) {
                case "offer":
                case "answer":
                case "ice-candidate":
                    // Forward the signaling message to the target peer
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

            // Check if the proof is expired
            String expiresAt = response.item().get("expiresAt").s();
            long expirationTime = Long.parseLong(expiresAt);
            return System.currentTimeMillis() < expirationTime;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates the WebRTC signaling message format
     * @param type The signaling message type
     * @param data The message data
     * @return Error message if validation fails, null if validation passes
     */
    private String validateSignalingMessage(String type, Map<String, Object> data) {
        // Check if the type is valid
        if (!VALID_SIGNALING_TYPES.contains(type)) {
            return "Invalid signaling type: " + type;
        }
        
        // Check for required fields based on the type
        Set<String> requiredFields = REQUIRED_FIELDS.get(type);
        if (requiredFields != null) {
            for (String field : requiredFields) {
                if (!data.containsKey(field) || data.get(field) == null) {
                    return "Missing required field for " + type + ": " + field;
                }
            }
        }
        
        // Validate SDP format for offer and answer
        if (type.equals("offer") || type.equals("answer")) {
            String sdp = (String) data.get("sdp");
            if (sdp == null || !sdp.startsWith("v=0")) {
                return "Invalid SDP format for " + type;
            }
        }
        
        // Validate ICE candidate format
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
            // Look up the peer's connection information in DynamoDB
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("peerId", AttributeValue.builder().s(peerId).build());

            GetItemRequest request = GetItemRequest.builder()
                .tableName(peerConnectionsTable)
                .key(key)
                .build();

            GetItemResponse response = dynamoDbClient.getItem(request);
            if (!response.hasItem()) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(404)
                    .withBody("Peer not found or not connected");
            }

            // Get the peer's connection information
            Map<String, AttributeValue> peerInfo = response.item();
            
            // Check if the peer is connected
            String status = peerInfo.get("status").s();
            if (!"connected".equals(status)) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Peer is not connected. Current status: " + status);
            }
            
            // Check if the connection has timed out
            String connectedAt = peerInfo.get("connectedAt").s();
            long connectionTime = Long.parseLong(connectedAt);
            long currentTime = System.currentTimeMillis();
            long connectionTimeout = 30 * 60 * 1000; // 30 minutes in milliseconds
            
            if (currentTime - connectionTime > connectionTimeout) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Peer connection has timed out");
            }
            
            String peerConnectionId = peerInfo.get("connectionId").s();
            String peerEndpoint = peerInfo.get("endpoint").s();

            // Prepare the signaling message to forward
            Map<String, Object> signalingMessage = new HashMap<>(data);
            // Only add fromPeerId if it exists in the data
            if (data.containsKey("fromPeerId")) {
                signalingMessage.put("fromPeerId", data.get("fromPeerId"));
            }
            signalingMessage.put("timestamp", System.currentTimeMillis());
            signalingMessage.put("targetPeerId", peerId);
            signalingMessage.put("targetConnectionId", peerConnectionId);
            signalingMessage.put("targetEndpoint", peerEndpoint);

            // Convert the message to JSON
            String messageBody = objectMapper.writeValueAsString(signalingMessage);

            // Send the message to the SQS queue
            SendMessageRequest sendMessageRequest = SendMessageRequest.builder()
                .queueUrl(signalingQueueUrl)
                .messageBody(messageBody)
                .build();

            try {
                SendMessageResponse sendMessageResponse = sqsClient.sendMessage(sendMessageRequest);
                
                // Log the signaling event
                String fromPeerId = data.containsKey("fromPeerId") ? (String) data.get("fromPeerId") : "unknown";
                context.getLogger().log("Forwarding " + type + " from " + fromPeerId + " to " + peerId + " (MessageId: " + sendMessageResponse.messageId() + ")");

                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("Signaling message forwarded");
            } catch (QueueDoesNotExistException e) {
                context.getLogger().log("Error: SQS queue does not exist: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("Internal server error: Signaling queue not found");
            } catch (InvalidMessageContentsException e) {
                context.getLogger().log("Error: Invalid message contents: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Invalid message format");
            } catch (SqsException e) {
                context.getLogger().log("Error sending message to SQS: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("Error forwarding signaling message: " + e.getMessage());
            }
        } catch (Exception e) {
            context.getLogger().log("Error handling signaling: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error forwarding signaling message: " + e.getMessage());
        }
    }
} 
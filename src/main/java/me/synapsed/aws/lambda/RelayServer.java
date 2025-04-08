package me.synapsed.aws.lambda;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

public class RelayServer implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String subscriptionProofsTable;

    public RelayServer() {
        this.dynamoDbClient = DynamoDbClient.builder().build();
        this.objectMapper = new ObjectMapper();
        this.subscriptionProofsTable = System.getenv("SUBSCRIPTION_PROOFS_TABLE");
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

            switch (type) {
                case "offer":
                case "answer":
                case "ice-candidate":
                    // Forward the signaling message to the target peer
                    return handleSignaling(type, peerId, requestBody);
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

    private APIGatewayProxyResponseEvent handleSignaling(String type, String peerId, Map<String, Object> data) {
        // TODO: Implement WebRTC signaling logic
        // This would involve:
        // 1. Looking up the peer's connection information
        // 2. Forwarding the signaling message to the peer
        // 3. Handling any errors that occur during the process

        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200)
            .withBody("Signaling message forwarded");
    }
} 
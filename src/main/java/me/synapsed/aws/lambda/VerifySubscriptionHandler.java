package me.synapsed.aws.lambda;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class VerifySubscriptionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDb;
    private final ObjectMapper objectMapper;
    private final String subscriptionsTable;
    private final String proofsTable;

    public VerifySubscriptionHandler(DynamoDbClient dynamoDb, Map<String, String> env) {
        this.dynamoDb = dynamoDb;
        this.objectMapper = new ObjectMapper();
        this.subscriptionsTable = env.getOrDefault("SUBSCRIPTIONS_TABLE", System.getenv("SUBSCRIPTIONS_TABLE"));
        this.proofsTable = env.getOrDefault("PROOFS_TABLE", System.getenv("PROOFS_TABLE"));
        
        // Initialize Stripe
        Stripe.apiKey = env.getOrDefault("STRIPE_SECRET_KEY", System.getenv("STRIPE_SECRET_KEY"));
    }

    public VerifySubscriptionHandler() {
        this(DynamoDbClient.builder().build(), new HashMap<>());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Get DID from headers
            String did = input.getHeaders().get("X-DID");
            if (did == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-DID header");
            }

            // Get subscription from DynamoDB
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("did", AttributeValue.builder().s(did).build());

            GetItemRequest getItemRequest = GetItemRequest.builder()
                .tableName(subscriptionsTable)
                .key(key)
                .build();

            GetItemResponse getItemResponse = dynamoDb.getItem(getItemRequest);
            if (!getItemResponse.hasItem()) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(404)
                    .withBody("No active subscription found");
            }

            Map<String, AttributeValue> item = getItemResponse.item();
            String status = item.get("status").s();
            String expiresAt = item.get("expiresAt").s();

            // Check if subscription is active and not expired
            if (!"active".equals(status)) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(403)
                    .withBody("Subscription is not active");
            }

            long expirationTime = Long.parseLong(expiresAt);
            if (System.currentTimeMillis() > expirationTime) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(403)
                    .withBody("Subscription has expired");
            }

            // Generate subscription proof
            String proof = generateSubscriptionProof(did, expiresAt);

            // Store proof in DynamoDB
            Map<String, AttributeValue> proofItem = new HashMap<>();
            proofItem.put("did", AttributeValue.builder().s(did).build());
            proofItem.put("proof", AttributeValue.builder().s(proof).build());
            proofItem.put("createdAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
            proofItem.put("expiresAt", AttributeValue.builder().s(expiresAt).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(proofsTable)
                .item(proofItem)
                .build();

            dynamoDb.putItem(putItemRequest);

            // Return proof
            Map<String, String> response = new HashMap<>();
            response.put("proof", proof);
            response.put("subscriptionId", item.get("subscriptionId").s());

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(response));

        } catch (Exception e) {
            context.getLogger().log("Error verifying subscription: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error verifying subscription: " + e.getMessage());
        }
    }

    private String generateSubscriptionProof(String did, String expiresAt) {
        // Create a signed proof of subscription
        // This is a simplified version - in production, you'd want to use a proper
        // zero-knowledge proof system
        return String.format("%s:%s", did, expiresAt);
    }
} 
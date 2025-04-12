package me.synapsed.aws.lambda;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

public class CreateSubscriptionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDb;
    private final ObjectMapper objectMapper;
    private final String subscriptionsTable;
    private final String proofsTable;
    private final Set<String> allowedPriceIds;
    private final long proofExpirationMs = 86400000; // 24 hours
    private final long proofCleanupThresholdMs = 604800000; // 7 days

    public CreateSubscriptionHandler(DynamoDbClient dynamoDb) {
        this(dynamoDb, System.getenv("SUBSCRIPTIONS_TABLE"),
             System.getenv("PROOFS_TABLE"),
             System.getenv("STRIPE_SECRET_KEY"),
             System.getenv("ALLOWED_PRICE_IDS"));
    }

    public CreateSubscriptionHandler() {
        this(DynamoDbClient.builder().build());
    }

    public CreateSubscriptionHandler(DynamoDbClient dynamoDb, String subscriptionsTable,
                                   String proofsTable, String stripeSecretKey,
                                   String allowedPriceIdsStr) {
        this.dynamoDb = dynamoDb;
        this.objectMapper = new ObjectMapper();
        this.subscriptionsTable = subscriptionsTable;
        this.proofsTable = proofsTable;
        
        // Initialize Stripe
        Stripe.apiKey = stripeSecretKey;
        
        // Initialize allowed price IDs
        this.allowedPriceIds = new HashSet<>();
        if (allowedPriceIdsStr != null && !allowedPriceIdsStr.isEmpty()) {
            this.allowedPriceIds.addAll(Arrays.asList(allowedPriceIdsStr.split(",")));
        }
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse request body
            Map<String, String> request = objectMapper.readValue(input.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            String did = request.get("did");
            String priceId = request.get("priceId");
            String idempotencyKey = request.get("idempotencyKey");
            Map<String, String> metadata = extractMetadata(request);

            // Validate required fields
            if (did == null || priceId == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing required fields: did, priceId");
            }

            // Validate price ID against allowed plans
            if (!isValidPriceId(priceId)) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Invalid price ID: " + priceId);
            }

            // Check for existing subscription with the same idempotency key
            if (idempotencyKey != null) {
                String existingSubscriptionId = findExistingSubscription(did, idempotencyKey);
                if (existingSubscriptionId != null) {
                    // Return the existing subscription details
                    Map<String, String> response = new HashMap<>();
                    response.put("subscriptionId", existingSubscriptionId);
                    response.put("status", "existing");
                    return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody(objectMapper.writeValueAsString(response));
                }
            }

            // Clean up expired subscription proofs
            cleanupExpiredProofs(did);

            // Create Stripe checkout session
            SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .addLineItem(
                    SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build()
                )
                .setSuccessUrl("https://synapsed.app/subscription/success?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl("https://synapsed.app/subscription/cancel")
                .setClientReferenceId(did);

            // Add metadata if provided
            if (metadata != null && !metadata.isEmpty()) {
                paramsBuilder.putAllMetadata(metadata);
            }

            // Add idempotency key if provided
            if (idempotencyKey != null) {
                // Note: Stripe's Session.create() doesn't support idempotency keys directly
                // We'll handle idempotency at the application level instead
            }

            Session session = Session.create(paramsBuilder.build());

            // Store subscription info in DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("did", AttributeValue.builder().s(did).build());
            item.put("stripeCustomerId", AttributeValue.builder().s(session.getCustomer()).build());
            item.put("stripeSubscriptionId", AttributeValue.builder().s(session.getSubscription()).build());
            item.put("priceId", AttributeValue.builder().s(priceId).build());
            item.put("status", AttributeValue.builder().s("pending").build());
            item.put("createdAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
            
            // Add idempotency key if provided
            if (idempotencyKey != null) {
                item.put("idempotencyKey", AttributeValue.builder().s(idempotencyKey).build());
            }
            
            // Add metadata if provided
            if (metadata != null && !metadata.isEmpty()) {
                Map<String, AttributeValue> metadataMap = new HashMap<>();
                for (Map.Entry<String, String> entry : metadata.entrySet()) {
                    metadataMap.put(entry.getKey(), AttributeValue.builder().s(entry.getValue()).build());
                }
                item.put("metadata", AttributeValue.builder().m(metadataMap).build());
            }

            PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(subscriptionsTable)
                .item(item)
                .build();

            try {
                dynamoDb.putItem(putItemRequest);
            } catch (ConditionalCheckFailedException e) {
                // Handle concurrent creation with the same idempotency key
                context.getLogger().log("Concurrent subscription creation detected: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(409)
                    .withBody("Concurrent subscription creation detected");
            }

            // Generate and store initial proof
            String proof = generateSubscriptionProof(did, session.getSubscription());
            Map<String, AttributeValue> proofItem = new HashMap<>();
            proofItem.put("did", AttributeValue.builder().s(did).build());
            proofItem.put("proof", AttributeValue.builder().s(proof).build());
            proofItem.put("createdAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
            proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + proofExpirationMs)).build());

            PutItemRequest proofRequest = PutItemRequest.builder()
                .tableName(proofsTable)
                .item(proofItem)
                .build();

            dynamoDb.putItem(proofRequest);

            // Return checkout session URL
            Map<String, String> response = new HashMap<>();
            response.put("checkoutUrl", session.getUrl());
            response.put("subscriptionId", session.getSubscription());

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(response));

        } catch (StripeException e) {
            context.getLogger().log("Stripe API error: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withBody("Stripe API error: " + e.getMessage());
        } catch (ResourceNotFoundException e) {
            context.getLogger().log("DynamoDB table not found: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Database configuration error");
        } catch (Exception e) {
            context.getLogger().log("Error creating subscription: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error creating subscription: " + e.getMessage());
        }
    }

    /**
     * Validates if the provided price ID is in the list of allowed price IDs
     */
    private boolean isValidPriceId(String priceId) {
        if (priceId == null || priceId.isEmpty()) {
            return false;
        }
        // If no allowed price IDs are configured, accept any non-empty price ID
        if (allowedPriceIds.isEmpty()) {
            return true;
        }
        return allowedPriceIds.contains(priceId);
    }

    /**
     * Finds an existing subscription with the given DID and idempotency key
     */
    private String findExistingSubscription(String did, String idempotencyKey) {
        try {
            Map<String, AttributeValue> keyCondition = new HashMap<>();
            keyCondition.put(":did", AttributeValue.builder().s(did).build());
            keyCondition.put(":idempotencyKey", AttributeValue.builder().s(idempotencyKey).build());

            QueryRequest queryRequest = QueryRequest.builder()
                .tableName(subscriptionsTable)
                .keyConditionExpression("did = :did AND idempotencyKey = :idempotencyKey")
                .expressionAttributeValues(keyCondition)
                .build();

            QueryResponse response = dynamoDb.query(queryRequest);
            if (response.hasItems() && !response.items().isEmpty()) {
                return response.items().get(0).get("stripeSubscriptionId").s();
            }
        } catch (Exception e) {
            // Log error but continue with subscription creation
        }
        return null;
    }

    /**
     * Cleans up expired subscription proofs for the given DID
     */
    private void cleanupExpiredProofs(String did) {
        try {
            long currentTime = System.currentTimeMillis();
            long thresholdTime = currentTime - proofCleanupThresholdMs;

            Map<String, AttributeValue> keyCondition = new HashMap<>();
            keyCondition.put(":did", AttributeValue.builder().s(did).build());
            keyCondition.put(":thresholdTime", AttributeValue.builder().s(String.valueOf(thresholdTime)).build());

            QueryRequest queryRequest = QueryRequest.builder()
                .tableName(proofsTable)
                .keyConditionExpression("did = :did")
                .filterExpression("createdAt < :thresholdTime")
                .expressionAttributeValues(keyCondition)
                .build();

            QueryResponse response = dynamoDb.query(queryRequest);
            if (response.hasItems()) {
                for (Map<String, AttributeValue> item : response.items()) {
                    String proof = item.get("proof").s();
                    Map<String, AttributeValue> key = new HashMap<>();
                    key.put("did", AttributeValue.builder().s(did).build());
                    key.put("proof", AttributeValue.builder().s(proof).build());

                    DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                        .tableName(proofsTable)
                        .key(key)
                        .build();

                    dynamoDb.deleteItem(deleteRequest);
                }
            }
        } catch (Exception e) {
            // Log error but continue with subscription creation
        }
    }

    /**
     * Extracts metadata from the request
     */
    private Map<String, String> extractMetadata(Map<String, String> request) {
        Map<String, String> metadata = new HashMap<>();
        for (Map.Entry<String, String> entry : request.entrySet()) {
            if (entry.getKey().startsWith("metadata_")) {
                String key = entry.getKey().substring("metadata_".length());
                metadata.put(key, entry.getValue());
            }
        }
        return metadata;
    }

    /**
     * Generates a subscription proof
     */
    private String generateSubscriptionProof(String did, String subscriptionId) {
        return String.format("%s:%s:%d", did, subscriptionId, System.currentTimeMillis());
    }
} 
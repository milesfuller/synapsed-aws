package me.synapsed.aws.lambda;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.net.Webhook;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

public class WebhookHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDb;
    private final String subscriptionsTable;
    private final String webhookSecret;
    private LambdaLogger logger;

    // Valid subscription status transitions
    private static final Map<String, Set<String>> VALID_STATUS_TRANSITIONS = new HashMap<>();
    static {
        Set<String> fromPending = new HashSet<>();
        fromPending.add("active");
        fromPending.add("incomplete");
        fromPending.add("canceled");
        VALID_STATUS_TRANSITIONS.put("pending", fromPending);

        Set<String> fromActive = new HashSet<>();
        fromActive.add("past_due");
        fromActive.add("canceled");
        fromActive.add("unpaid");
        VALID_STATUS_TRANSITIONS.put("active", fromActive);

        Set<String> fromPastDue = new HashSet<>();
        fromPastDue.add("active");
        fromPastDue.add("canceled");
        fromPastDue.add("unpaid");
        VALID_STATUS_TRANSITIONS.put("past_due", fromPastDue);
    }

    // Constructor for testing
    public WebhookHandler(DynamoDbClient dynamoDbClient, Map<String, String> env) {
        this.dynamoDb = dynamoDbClient;
        this.subscriptionsTable = env.getOrDefault("SUBSCRIPTIONS_TABLE", System.getenv("SUBSCRIPTIONS_TABLE"));
        this.webhookSecret = env.getOrDefault("STRIPE_WEBHOOK_SECRET", System.getenv("STRIPE_WEBHOOK_SECRET"));
        
        // Initialize Stripe
        Stripe.apiKey = env.getOrDefault("STRIPE_SECRET_KEY", System.getenv("STRIPE_SECRET_KEY"));
    }

    public WebhookHandler() {
        this(DynamoDbClient.create(), new HashMap<>());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        this.logger = context.getLogger();
        try {
            // Verify the webhook signature
            String signature = input.getHeaders().get("Stripe-Signature");
            if (signature == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing Stripe-Signature header");
            }

            Event event = Webhook.constructEvent(
                input.getBody(),
                signature,
                webhookSecret
            );

            // Handle different event types
            switch (event.getType()) {
                case "customer.subscription.created":
                case "customer.subscription.updated":
                    return handleSubscriptionEvent(event);
                case "customer.subscription.deleted":
                    return handleSubscriptionCancellation(event);
                default:
                    logger.log("Unhandled event type: " + event.getType());
                    return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody("Webhook received but not processed");
            }

        } catch (com.stripe.exception.SignatureVerificationException e) {
            logger.log("Invalid signature: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withBody("Invalid signature");
        } catch (Exception e) {
            logger.log("Error processing webhook: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error processing webhook: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent handleSubscriptionEvent(Event event) {
        try {
            Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .deserializeUnsafe();
            String subscriptionId = subscription.getId();
            
            // Get current subscription status if it exists
            GetItemRequest getRequest = GetItemRequest.builder()
                .tableName(subscriptionsTable)
                .key(Map.of("id", AttributeValue.builder().s(subscriptionId).build()))
                .build();

            GetItemResponse getResponse = dynamoDb.getItem(getRequest);
            String currentStatus = getResponse.hasItem() ? 
                getResponse.item().get("status").s() : "pending";

            // Validate status transition
            String newStatus = subscription.getStatus();
            if (!isValidStatusTransition(currentStatus, newStatus)) {
                logger.log(String.format("Invalid status transition from %s to %s for subscription %s", 
                    currentStatus, newStatus, subscriptionId));
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Invalid subscription status transition");
            }

            // Build subscription metadata
            Map<String, AttributeValue> metadata = new HashMap<>();
            if (subscription.getMetadata() != null) {
                subscription.getMetadata().forEach((key, value) -> 
                    metadata.put("metadata_" + key, AttributeValue.builder().s(value).build())
                );
            }

            // Add subscription items information
            Map<String, AttributeValue> items = new HashMap<>();
            for (SubscriptionItem item : subscription.getItems().getData()) {
                items.put("item_" + item.getPrice().getId(), AttributeValue.builder()
                    .m(Map.of(
                        "quantity", AttributeValue.builder().n(String.valueOf(item.getQuantity())).build(),
                        "price", AttributeValue.builder().s(item.getPrice().getId()).build()
                    ))
                    .build());
            }

            // Build the complete item
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(subscriptionId).build());
            item.put("status", AttributeValue.builder().s(newStatus).build());
            item.put("customerId", AttributeValue.builder().s(subscription.getCustomer()).build());
            item.put("currentPeriodEnd", AttributeValue.builder().n(String.valueOf(subscription.getCurrentPeriodEnd())).build());
            item.put("currentPeriodStart", AttributeValue.builder().n(String.valueOf(subscription.getCurrentPeriodStart())).build());
            item.put("cancelAtPeriodEnd", AttributeValue.builder().bool(subscription.getCancelAtPeriodEnd()).build());
            item.put("items", AttributeValue.builder().m(items).build());
            item.put("metadata", AttributeValue.builder().m(metadata).build());
            item.put("updatedAt", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis())).build());

            try {
                PutItemRequest putRequest = PutItemRequest.builder()
                    .tableName(subscriptionsTable)
                    .item(item)
                    .build();

                dynamoDb.putItem(putRequest);
                
                logger.log(String.format("Successfully updated subscription %s with status %s", 
                    subscriptionId, newStatus));
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("Subscription updated successfully");

            } catch (ResourceNotFoundException e) {
                logger.log("DynamoDB table not found: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("Database configuration error");
            } catch (ConditionalCheckFailedException e) {
                logger.log("Conditional check failed: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(409)
                    .withBody("Concurrent modification error");
            } catch (Exception e) {
                logger.log("Error updating subscription: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("Error updating subscription: " + e.getMessage());
            }
        } catch (Exception e) {
            logger.log("Error handling subscription event: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error processing subscription event");
        }
    }

    private APIGatewayProxyResponseEvent handleSubscriptionCancellation(Event event) {
        try {
            Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .deserializeUnsafe();
            String subscriptionId = subscription.getId();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(subscriptionId).build());
            item.put("status", AttributeValue.builder().s("cancelled").build());
            item.put("canceledAt", AttributeValue.builder().n(String.valueOf(subscription.getCanceledAt())).build());
            item.put("cancelReason", AttributeValue.builder().s(
                subscription.getMetadata().getOrDefault("cancel_reason", "user_requested")).build());
            item.put("updatedAt", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis())).build());

            try {
                PutItemRequest putRequest = PutItemRequest.builder()
                    .tableName(subscriptionsTable)
                    .item(item)
                    .build();

                dynamoDb.putItem(putRequest);
                
                logger.log(String.format("Successfully cancelled subscription %s", subscriptionId));
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody("Subscription cancelled successfully");

            } catch (ResourceNotFoundException | ConditionalCheckFailedException e) {
                logger.log("Error updating DynamoDB: " + e.getMessage());
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("Error updating subscription status");
            }
        } catch (Exception e) {
            logger.log("Error handling subscription cancellation: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error processing subscription cancellation");
        }
    }

    private boolean isValidStatusTransition(String currentStatus, String newStatus) {
        if (currentStatus.equals(newStatus)) {
            return true;
        }
        Set<String> validTransitions = VALID_STATUS_TRANSITIONS.get(currentStatus);
        return validTransitions != null && validTransitions.contains(newStatus);
    }
} 
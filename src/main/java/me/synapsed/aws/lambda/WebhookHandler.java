package me.synapsed.aws.lambda;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.stripe.Stripe;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.LambdaLogger;

public class WebhookHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final String subscriptionsTable;
    private final DynamoDbClient dynamoDbClient;
    private LambdaLogger logger;
    private final String webhookSecret;

    public WebhookHandler() {
        this(DynamoDbClient.builder().build(), null);
    }

    // Constructor for testing
    WebhookHandler(DynamoDbClient dynamoDbClient) {
        this(dynamoDbClient, null);
    }

    // Constructor for testing with environment
    WebhookHandler(DynamoDbClient dynamoDbClient, Map<String, String> env) {
        this.dynamoDbClient = dynamoDbClient;
        Map<String, String> environment = env != null ? env : System.getenv();
        this.subscriptionsTable = environment.get("SUBSCRIPTIONS_TABLE");
        this.webhookSecret = environment.get("STRIPE_WEBHOOK_SECRET");
        
        // Initialize Stripe
        Stripe.apiKey = environment.get("STRIPE_SECRET_KEY");
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
                    handleSubscriptionEvent(event);
                    break;
                case "customer.subscription.deleted":
                    handleSubscriptionCancellation(event);
                    break;
                default:
                    logger.log("Unhandled event type: " + event.getType());
            }

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("Webhook processed successfully");

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

    private void handleSubscriptionEvent(Event event) {
        try {
            Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .deserializeUnsafe();
            String subscriptionId = subscription.getId();
            
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(subscriptionId).build());
            item.put("status", AttributeValue.builder().s("active").build());
            item.put("expiresAt", AttributeValue.builder().n(String.valueOf(subscription.getCurrentPeriodEnd())).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(subscriptionsTable)
                    .item(item)
                    .build();

            dynamoDbClient.putItem(putItemRequest);
        } catch (Exception e) {
            logger.log("Error handling subscription event: " + e.getMessage());
        }
    }

    private void handleSubscriptionCancellation(Event event) {
        try {
            Subscription subscription = (Subscription) event.getDataObjectDeserializer()
                .deserializeUnsafe();
            String subscriptionId = subscription.getId();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", AttributeValue.builder().s(subscriptionId).build());
            item.put("status", AttributeValue.builder().s("cancelled").build());
            item.put("expiresAt", AttributeValue.builder().n(String.valueOf(subscription.getCurrentPeriodEnd())).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(subscriptionsTable)
                    .item(item)
                    .build();

            dynamoDbClient.putItem(putItemRequest);
        } catch (Exception e) {
            logger.log("Error handling subscription cancellation: " + e.getMessage());
        }
    }
} 
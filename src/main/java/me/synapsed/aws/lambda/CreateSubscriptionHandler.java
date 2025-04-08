package me.synapsed.aws.lambda;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.Stripe;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class CreateSubscriptionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDb;
    private final ObjectMapper objectMapper;
    private final String subscriptionsTable;
    private final String proofsTable;

    public CreateSubscriptionHandler(DynamoDbClient dynamoDb) {
        this.dynamoDb = dynamoDb;
        this.objectMapper = new ObjectMapper();
        this.subscriptionsTable = System.getenv("SUBSCRIPTIONS_TABLE");
        this.proofsTable = System.getenv("PROOFS_TABLE");
        
        // Initialize Stripe
        Stripe.apiKey = System.getenv("STRIPE_SECRET_KEY");
    }

    public CreateSubscriptionHandler() {
        this(DynamoDbClient.builder().build());
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Parse request body
            Map<String, String> request = objectMapper.readValue(input.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            String did = request.get("did");
            String priceId = request.get("priceId");

            if (did == null || priceId == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing required fields: did, priceId");
            }

            // Create Stripe checkout session
            SessionCreateParams params = SessionCreateParams.builder()
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
                .setClientReferenceId(did)
                .build();

            Session session = Session.create(params);

            // Store subscription info in DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("did", AttributeValue.builder().s(did).build());
            item.put("stripeCustomerId", AttributeValue.builder().s(session.getCustomer()).build());
            item.put("stripeSubscriptionId", AttributeValue.builder().s(session.getSubscription()).build());
            item.put("status", AttributeValue.builder().s("pending").build());
            item.put("createdAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(subscriptionsTable)
                .item(item)
                .build();

            dynamoDb.putItem(putItemRequest);

            // Generate and store initial proof
            String proof = String.format("%s:%s:%d", did, session.getSubscription(), System.currentTimeMillis());
            Map<String, AttributeValue> proofItem = new HashMap<>();
            proofItem.put("did", AttributeValue.builder().s(did).build());
            proofItem.put("proof", AttributeValue.builder().s(proof).build());
            proofItem.put("createdAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
            proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 86400000)).build()); // 24 hours

            PutItemRequest proofRequest = PutItemRequest.builder()
                .tableName(proofsTable)
                .item(proofItem)
                .build();

            dynamoDb.putItem(proofRequest);

            // Return checkout session URL
            Map<String, String> response = new HashMap<>();
            response.put("checkoutUrl", session.getUrl());

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(response));

        } catch (Exception e) {
            context.getLogger().log("Error creating subscription: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error creating subscription: " + e.getMessage());
        }
    }
} 
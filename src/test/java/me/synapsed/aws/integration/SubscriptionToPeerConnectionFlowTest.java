package me.synapsed.aws.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.model.Event;
import com.stripe.model.Event.Data;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Price;
import com.stripe.model.Subscription;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;

import me.synapsed.aws.lambda.CreateSubscriptionHandler;
import me.synapsed.aws.lambda.PeerConnectionHandler;
import me.synapsed.aws.lambda.VerifySubscriptionHandler;
import me.synapsed.aws.lambda.WebhookHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public class SubscriptionToPeerConnectionFlowTest {
    @Mock
    private DynamoDbClient dynamoDbClient;
    
    @Mock
    private Context context;
    
    @Mock
    private LambdaLogger logger;
    
    private CreateSubscriptionHandler createSubscriptionHandler;
    private WebhookHandler webhookHandler;
    private VerifySubscriptionHandler verifySubscriptionHandler;
    private PeerConnectionHandler peerConnectionHandler;
    private ObjectMapper objectMapper;
    
    private static final String SUBSCRIPTIONS_TABLE = "test-subscriptions-table";
    private static final String PROOFS_TABLE = "test-proofs-table";
    private static final String PEER_CONNECTIONS_TABLE = "test-peer-connections-table";
    private static final String STRIPE_SECRET_KEY = "test-stripe-secret-key";
    private static final String STRIPE_WEBHOOK_SECRET = "test-webhook-secret";
    private static final String ALLOWED_PRICE_IDS = "price_123,price_456";
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(context.getLogger()).thenReturn(logger);
        
        // Set up environment variables map for testing
        Map<String, String> envVars = new HashMap<>();
        envVars.put("SUBSCRIPTIONS_TABLE", SUBSCRIPTIONS_TABLE);
        envVars.put("PROOFS_TABLE", PROOFS_TABLE);
        envVars.put("PEER_CONNECTIONS_TABLE", PEER_CONNECTIONS_TABLE);
        envVars.put("STRIPE_SECRET_KEY", STRIPE_SECRET_KEY);
        envVars.put("STRIPE_WEBHOOK_SECRET", STRIPE_WEBHOOK_SECRET);
        envVars.put("ALLOWED_PRICE_IDS", ALLOWED_PRICE_IDS);
        
        // Initialize handlers with mocked DynamoDB client and environment variables
        createSubscriptionHandler = new CreateSubscriptionHandler(dynamoDbClient, 
            SUBSCRIPTIONS_TABLE,
            PROOFS_TABLE,
            STRIPE_SECRET_KEY,
            ALLOWED_PRICE_IDS);
        webhookHandler = new WebhookHandler(dynamoDbClient, envVars);
        verifySubscriptionHandler = new VerifySubscriptionHandler(dynamoDbClient, envVars);
        peerConnectionHandler = new PeerConnectionHandler(dynamoDbClient, envVars);
        objectMapper = new ObjectMapper();
    }
    
    @Test
    void testCompleteSubscriptionToPeerConnectionFlow() throws Exception {
        // Mock Stripe Session
        Session mockSession = mock(Session.class);
        when(mockSession.getCustomer()).thenReturn("cus_123");
        when(mockSession.getSubscription()).thenReturn("sub_123");
        when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");

        try (MockedStatic<Session> mockedSession = mockStatic(Session.class);
             MockedStatic<Webhook> mockedWebhook = mockStatic(Webhook.class)) {
            mockedSession.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenReturn(mockSession);

            // Step 1: Create subscription
            APIGatewayProxyRequestEvent createRequest = new APIGatewayProxyRequestEvent();
            Map<String, String> createBody = new HashMap<>();
            createBody.put("did", "did:example:123");
            createBody.put("priceId", "price_123");
            createBody.put("customerId", "cus_123");
            createRequest.setBody(objectMapper.writeValueAsString(createBody));
            
            // Mock DynamoDB responses for create subscription
            when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().build());
            when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());
            
            APIGatewayProxyResponseEvent createResponse = createSubscriptionHandler.handleRequest(createRequest, context);
            assertEquals(200, createResponse.getStatusCode());
            assertNotNull(createResponse.getBody());
            
            // Mock DynamoDB response for subscription verification
            Map<String, AttributeValue> subscriptionItem = new HashMap<>();
            subscriptionItem.put("did", AttributeValue.builder().s("did:example:123").build());
            subscriptionItem.put("status", AttributeValue.builder().s("active").build());
            subscriptionItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
            subscriptionItem.put("subscriptionId", AttributeValue.builder().s("sub_123").build());
            
            when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(subscriptionItem).build());
            
            // Step 2: Simulate webhook event for subscription activation
            APIGatewayProxyRequestEvent webhookRequest = new APIGatewayProxyRequestEvent();
            Map<String, String> webhookHeaders = new HashMap<>();
            webhookHeaders.put("Stripe-Signature", "test-signature");
            webhookRequest.setHeaders(webhookHeaders);

            Map<String, String> webhookBody = new HashMap<>();
            webhookBody.put("type", "customer.subscription.created");
            webhookBody.put("data", "{\"object\":{\"id\":\"sub_123\",\"status\":\"active\"}}");
            String webhookBodyStr = objectMapper.writeValueAsString(webhookBody);
            webhookRequest.setBody(webhookBodyStr);
            
            // Mock Stripe Event and related objects
            Event mockEvent = mock(Event.class);
            Data mockEventData = mock(Data.class);
            EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
            Subscription mockSubscription = mock(Subscription.class);
            SubscriptionItemCollection mockItemCollection = mock(SubscriptionItemCollection.class);
            SubscriptionItem mockSubscriptionItem = mock(SubscriptionItem.class);
            Price mockPrice = mock(Price.class);

            // Set up mock behavior
            when(mockEvent.getType()).thenReturn("customer.subscription.created");
            when(mockEvent.getData()).thenReturn(mockEventData);
            when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
            when(mockDeserializer.deserializeUnsafe()).thenReturn(mockSubscription);

            when(mockSubscription.getId()).thenReturn("sub_123");
            when(mockSubscription.getStatus()).thenReturn("active");
            when(mockSubscription.getCustomer()).thenReturn("cus_123");
            when(mockSubscription.getCurrentPeriodEnd()).thenReturn(1735689600L);
            when(mockSubscription.getCurrentPeriodStart()).thenReturn(1735603200L);
            when(mockSubscription.getCancelAtPeriodEnd()).thenReturn(false);
            when(mockSubscription.getMetadata()).thenReturn(Collections.emptyMap());
            when(mockSubscription.getItems()).thenReturn(mockItemCollection);

            when(mockItemCollection.getData()).thenReturn(List.of(mockSubscriptionItem));
            when(mockSubscriptionItem.getPrice()).thenReturn(mockPrice);
            when(mockSubscriptionItem.getQuantity()).thenReturn(1L);
            when(mockPrice.getId()).thenReturn("price_123");

            mockedWebhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);

            APIGatewayProxyResponseEvent webhookResponse = webhookHandler.handleRequest(webhookRequest, context);
            assertEquals(200, webhookResponse.getStatusCode());
            
            // Step 3: Verify subscription and get proof
            APIGatewayProxyRequestEvent verifyRequest = new APIGatewayProxyRequestEvent();
            
            // Add X-DID header - this should match the DID used in subscription creation
            Map<String, String> headers = new HashMap<>();
            headers.put("X-DID", "did:example:123");
            verifyRequest.setHeaders(headers);
            
            // No need for request body since we look up by DID
            verifyRequest.setBody("{}");
            
            APIGatewayProxyResponseEvent verifyResponse = verifySubscriptionHandler.handleRequest(verifyRequest, context);
            assertEquals(200, verifyResponse.getStatusCode());
            assertNotNull(verifyResponse.getBody());
            
            // Parse response to get proof
            Map<String, String> verifyResponseBody = objectMapper.readValue(verifyResponse.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            String proof = verifyResponseBody.get("proof");
            assertNotNull(proof);
            
            // Mock DynamoDB responses for peer connection
            // 1. Mock subscription proof verification
            Map<String, AttributeValue> proofItem = new HashMap<>();
            proofItem.put("did", AttributeValue.builder().s("did:example:123").build());
            proofItem.put("proof", AttributeValue.builder().s(proof).build());
            proofItem.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
            
            when(dynamoDbClient.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(proofItem).build());
            
            // 2. Mock peer connection query (no existing connections)
            when(dynamoDbClient.query(any(QueryRequest.class)))
                .thenReturn(QueryResponse.builder().build());
            
            // 3. Mock peer connection creation
            when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenReturn(PutItemResponse.builder().build());
            
            // Step 4: Connect peer using subscription proof
            APIGatewayProxyRequestEvent connectRequest = new APIGatewayProxyRequestEvent();
            
            // Add headers
            Map<String, String> connectHeaders = new HashMap<>();
            connectHeaders.put("X-DID", "did:example:123");
            connectHeaders.put("X-Subscription-Proof", proof);
            connectRequest.setHeaders(connectHeaders);
            
            // Add path parameters
            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("action", "connect");
            connectRequest.setPathParameters(pathParams);
            
            // Add request context for source IP
            APIGatewayProxyRequestEvent.ProxyRequestContext requestContext = new APIGatewayProxyRequestEvent.ProxyRequestContext();
            APIGatewayProxyRequestEvent.RequestIdentity identity = new APIGatewayProxyRequestEvent.RequestIdentity();
            identity.setSourceIp("127.0.0.1");
            requestContext.setIdentity(identity);
            connectRequest.setRequestContext(requestContext);
            
            // Empty body since we're using headers
            connectRequest.setBody("{}");
            
            APIGatewayProxyResponseEvent connectResponse = peerConnectionHandler.handleRequest(connectRequest, context);
            assertEquals(200, connectResponse.getStatusCode());
            assertNotNull(connectResponse.getBody());
            
            // Parse response to get peer ID
            Map<String, String> connectResponseBody = objectMapper.readValue(connectResponse.getBody(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            String peerId = connectResponseBody.get("peerId");
            assertNotNull(peerId);
        }
    }

    @Test
    void testSubscriptionStatusTransitionActiveToCanceled() throws Exception {
        // Mock DynamoDB response for existing subscription
        Map<String, AttributeValue> existingSubscription = new HashMap<>();
        existingSubscription.put("id", AttributeValue.builder().s("sub_123").build());
        existingSubscription.put("status", AttributeValue.builder().s("active").build());
        existingSubscription.put("customerId", AttributeValue.builder().s("cus_123").build());
        existingSubscription.put("currentPeriodEnd", AttributeValue.builder().n("1735689600").build());
        existingSubscription.put("currentPeriodStart", AttributeValue.builder().n("1735603200").build());
        existingSubscription.put("cancelAtPeriodEnd", AttributeValue.builder().bool(false).build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(existingSubscription).build());
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        // Create webhook request for subscription cancellation
        APIGatewayProxyRequestEvent webhookRequest = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "test-signature");
        webhookRequest.setHeaders(headers);

        // Mock Stripe Event and related objects
        Event mockEvent = mock(Event.class);
        Data mockEventData = mock(Data.class);
        EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
        Subscription mockSubscription = mock(Subscription.class);
        SubscriptionItemCollection mockItemCollection = mock(SubscriptionItemCollection.class);
        SubscriptionItem mockSubscriptionItem = mock(SubscriptionItem.class);
        Price mockPrice = mock(Price.class);

        // Set up mock behavior for subscription update
        when(mockEvent.getType()).thenReturn("customer.subscription.updated");
        when(mockEvent.getData()).thenReturn(mockEventData);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
        when(mockDeserializer.deserializeUnsafe()).thenReturn(mockSubscription);

        when(mockSubscription.getId()).thenReturn("sub_123");
        when(mockSubscription.getStatus()).thenReturn("canceled");
        when(mockSubscription.getCustomer()).thenReturn("cus_123");
        when(mockSubscription.getCurrentPeriodEnd()).thenReturn(1735689600L);
        when(mockSubscription.getCurrentPeriodStart()).thenReturn(1735603200L);
        when(mockSubscription.getCancelAtPeriodEnd()).thenReturn(true);
        when(mockSubscription.getMetadata()).thenReturn(Collections.emptyMap());
        when(mockSubscription.getItems()).thenReturn(mockItemCollection);

        when(mockItemCollection.getData()).thenReturn(List.of(mockSubscriptionItem));
        when(mockSubscriptionItem.getPrice()).thenReturn(mockPrice);
        when(mockSubscriptionItem.getQuantity()).thenReturn(1L);
        when(mockPrice.getId()).thenReturn("price_123");

        try (MockedStatic<Webhook> mockedWebhook = mockStatic(Webhook.class)) {
            mockedWebhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);

            // Create webhook request body
            Map<String, Object> webhookBody = new HashMap<>();
            webhookBody.put("type", "customer.subscription.updated");
            webhookBody.put("data", Map.of("object", Map.of(
                "id", "sub_123",
                "status", "canceled",
                "customer", "cus_123"
            )));
            webhookRequest.setBody(objectMapper.writeValueAsString(webhookBody));

            // Send webhook request
            APIGatewayProxyResponseEvent response = webhookHandler.handleRequest(webhookRequest, context);

            // Verify response
            assertEquals(200, response.getStatusCode());
            assertEquals("Subscription updated successfully", response.getBody());

            // Verify DynamoDB was called to update the subscription
            verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
        }
    }

    @Test
    void testSubscriptionUpdateWithMetadataChanges() throws Exception {
        // Mock DynamoDB response for existing subscription
        Map<String, AttributeValue> existingSubscription = new HashMap<>();
        existingSubscription.put("id", AttributeValue.builder().s("sub_123").build());
        existingSubscription.put("status", AttributeValue.builder().s("active").build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(existingSubscription).build());
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        // Create webhook request
        APIGatewayProxyRequestEvent webhookRequest = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "test-signature");
        webhookRequest.setHeaders(headers);

        // Mock Stripe objects
        Event mockEvent = mock(Event.class);
        EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
        Subscription mockSubscription = mock(Subscription.class);
        SubscriptionItemCollection mockItemCollection = mock(SubscriptionItemCollection.class);
        SubscriptionItem mockSubscriptionItem = mock(SubscriptionItem.class);
        Price mockPrice = mock(Price.class);

        // Set up metadata changes
        Map<String, String> updatedMetadata = new HashMap<>();
        updatedMetadata.put("plan_type", "premium");
        updatedMetadata.put("user_tier", "enterprise");

        when(mockEvent.getType()).thenReturn("customer.subscription.updated");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
        when(mockDeserializer.deserializeUnsafe()).thenReturn(mockSubscription);
        when(mockSubscription.getId()).thenReturn("sub_123");
        when(mockSubscription.getStatus()).thenReturn("active");
        when(mockSubscription.getMetadata()).thenReturn(updatedMetadata);
        when(mockSubscription.getItems()).thenReturn(mockItemCollection);
        when(mockItemCollection.getData()).thenReturn(List.of(mockSubscriptionItem));
        when(mockSubscriptionItem.getPrice()).thenReturn(mockPrice);
        when(mockPrice.getId()).thenReturn("price_123");

        try (MockedStatic<Webhook> mockedWebhook = mockStatic(Webhook.class)) {
            mockedWebhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);

            Map<String, Object> webhookBody = new HashMap<>();
            webhookBody.put("type", "customer.subscription.updated");
            webhookBody.put("data", Map.of("object", Map.of(
                "id", "sub_123",
                "status", "active",
                "metadata", updatedMetadata
            )));
            webhookRequest.setBody(objectMapper.writeValueAsString(webhookBody));

            APIGatewayProxyResponseEvent response = webhookHandler.handleRequest(webhookRequest, context);

            assertEquals(200, response.getStatusCode());
            assertEquals("Subscription updated successfully", response.getBody());
            verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
        }
    }

    @Test
    void testSubscriptionUpdateWithPriceChanges() throws Exception {
        // Mock DynamoDB response for existing subscription
        Map<String, AttributeValue> existingSubscription = new HashMap<>();
        existingSubscription.put("id", AttributeValue.builder().s("sub_123").build());
        existingSubscription.put("status", AttributeValue.builder().s("active").build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(existingSubscription).build());
        when(dynamoDbClient.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        // Create webhook request
        APIGatewayProxyRequestEvent webhookRequest = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "test-signature");
        webhookRequest.setHeaders(headers);

        // Mock Stripe objects
        Event mockEvent = mock(Event.class);
        EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
        Subscription mockSubscription = mock(Subscription.class);
        SubscriptionItemCollection mockItemCollection = mock(SubscriptionItemCollection.class);
        SubscriptionItem mockSubscriptionItem = mock(SubscriptionItem.class);
        Price mockPrice = mock(Price.class);

        when(mockEvent.getType()).thenReturn("customer.subscription.updated");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
        when(mockDeserializer.deserializeUnsafe()).thenReturn(mockSubscription);
        when(mockSubscription.getId()).thenReturn("sub_123");
        when(mockSubscription.getStatus()).thenReturn("active");
        when(mockSubscription.getItems()).thenReturn(mockItemCollection);
        when(mockItemCollection.getData()).thenReturn(List.of(mockSubscriptionItem));
        when(mockSubscriptionItem.getPrice()).thenReturn(mockPrice);
        when(mockSubscriptionItem.getQuantity()).thenReturn(2L); // Updated quantity
        when(mockPrice.getId()).thenReturn("price_456"); // New price ID

        try (MockedStatic<Webhook> mockedWebhook = mockStatic(Webhook.class)) {
            mockedWebhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);

            Map<String, Object> webhookBody = new HashMap<>();
            webhookBody.put("type", "customer.subscription.updated");
            webhookBody.put("data", Map.of("object", Map.of(
                "id", "sub_123",
                "status", "active",
                "items", Map.of("data", List.of(Map.of(
                    "price", Map.of("id", "price_456"),
                    "quantity", 2
                )))
            )));
            webhookRequest.setBody(objectMapper.writeValueAsString(webhookBody));

            APIGatewayProxyResponseEvent response = webhookHandler.handleRequest(webhookRequest, context);

            assertEquals(200, response.getStatusCode());
            assertEquals("Subscription updated successfully", response.getBody());
            verify(dynamoDbClient, times(1)).putItem(any(PutItemRequest.class));
        }
    }

    @Test
    void testInvalidWebhookSignature() throws Exception {
        APIGatewayProxyRequestEvent webhookRequest = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "invalid-signature");
        webhookRequest.setHeaders(headers);

        try (MockedStatic<Webhook> mockedWebhook = mockStatic(Webhook.class)) {
            mockedWebhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenThrow(new com.stripe.exception.SignatureVerificationException("Invalid signature", "raw-payload"));

            Map<String, Object> webhookBody = new HashMap<>();
            webhookBody.put("type", "customer.subscription.updated");
            webhookBody.put("data", Map.of("object", Map.of("id", "sub_123")));
            webhookRequest.setBody(objectMapper.writeValueAsString(webhookBody));

            APIGatewayProxyResponseEvent response = webhookHandler.handleRequest(webhookRequest, context);

            assertEquals(400, response.getStatusCode());
            assertEquals("Invalid signature", response.getBody());
        }
    }

    @Test
    void testSubscriptionUpdateInvalidTransition() throws Exception {
        // Mock DynamoDB response for existing subscription
        Map<String, AttributeValue> existingSubscription = new HashMap<>();
        existingSubscription.put("id", AttributeValue.builder().s("sub_123").build());
        existingSubscription.put("status", AttributeValue.builder().s("active").build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(existingSubscription).build());

        // Create webhook request
        APIGatewayProxyRequestEvent webhookRequest = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "test-signature");
        webhookRequest.setHeaders(headers);

        // Mock Stripe objects
        Event mockEvent = mock(Event.class);
        EventDataObjectDeserializer mockDeserializer = mock(EventDataObjectDeserializer.class);
        Subscription mockSubscription = mock(Subscription.class);

        when(mockEvent.getType()).thenReturn("customer.subscription.updated");
        when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
        when(mockDeserializer.deserializeUnsafe()).thenReturn(mockSubscription);
        when(mockSubscription.getId()).thenReturn("sub_123");
        when(mockSubscription.getStatus()).thenReturn("incomplete_expired"); // Invalid transition

        try (MockedStatic<Webhook> mockedWebhook = mockStatic(Webhook.class)) {
            mockedWebhook.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);

            Map<String, Object> webhookBody = new HashMap<>();
            webhookBody.put("type", "customer.subscription.updated");
            webhookBody.put("data", Map.of("object", Map.of(
                "id", "sub_123",
                "status", "incomplete_expired"
            )));
            webhookRequest.setBody(objectMapper.writeValueAsString(webhookBody));

            APIGatewayProxyResponseEvent response = webhookHandler.handleRequest(webhookRequest, context);

            assertEquals(403, response.getStatusCode());
            assertEquals("Invalid subscription status transition", response.getBody());
        }
    }
}
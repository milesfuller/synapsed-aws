package me.synapsed.aws.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.stripe.model.Event;
import com.stripe.model.Event.Data;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.Price;
import com.stripe.model.SubscriptionItem;
import com.stripe.model.SubscriptionItemCollection;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Subscription;
import com.stripe.net.Webhook;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookHandlerTest {

    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private Context context;

    @Mock
    private LambdaLogger logger;

    @Mock
    private Event mockEvent;

    @Mock
    private Data mockEventData;

    @Mock
    private EventDataObjectDeserializer mockDeserializer;

    @Mock
    private Subscription mockSubscription;

    @Mock
    private SubscriptionItem mockSubscriptionItem;

    @Mock
    private Price mockPrice;

    private WebhookHandler handler;
    private Map<String, String> testEnv;

    @BeforeEach
    void setUp() throws EventDataObjectDeserializationException {
        MockitoAnnotations.openMocks(this);
        when(context.getLogger()).thenReturn(logger);
        
        // Set up test environment
        testEnv = new HashMap<>();
        testEnv.put("SUBSCRIPTIONS_TABLE", "test-subscriptions-table");
        testEnv.put("STRIPE_WEBHOOK_SECRET", "test-webhook-secret");
        testEnv.put("STRIPE_SECRET_KEY", "test-stripe-key");
        
        handler = new WebhookHandler(dynamoDb, testEnv);
        
        // Mock DynamoDB response
        when(dynamoDb.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());

        // Mock event data structure
        when(mockEvent.getData()).thenReturn(mockEventData);
        when(mockEvent.getDataObjectDeserializer()).thenReturn(mockDeserializer);
        when(mockDeserializer.deserializeUnsafe()).thenReturn(mockSubscription);
        when(mockSubscription.getId()).thenReturn("sub_123");
        when(mockSubscription.getCurrentPeriodEnd()).thenReturn(1735689600L);
        when(mockSubscription.getCurrentPeriodStart()).thenReturn(1735603200L);
        when(mockSubscription.getCustomer()).thenReturn("cus_123");
        when(mockSubscription.getCancelAtPeriodEnd()).thenReturn(false);
        when(mockSubscription.getStatus()).thenReturn("active");
        
        // Mock subscription items
        SubscriptionItemCollection itemCollection = new SubscriptionItemCollection();
        when(mockSubscriptionItem.getPrice()).thenReturn(mockPrice);
        when(mockPrice.getId()).thenReturn("price_123");
        when(mockSubscriptionItem.getQuantity()).thenReturn(1L);
        itemCollection.setData(List.of(mockSubscriptionItem));
        when(mockSubscription.getItems()).thenReturn(itemCollection);
    }

    @Test
    void testHandleRequest_MissingSignature() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setHeaders(new HashMap<>());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertEquals("Missing Stripe-Signature header", response.getBody());
    }

    @Test
    void testHandleRequest_InvalidSignature() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "invalid-signature");
        request.setHeaders(headers);
        request.setBody("{}");

        try (MockedStatic<Webhook> webhookMockedStatic = mockStatic(Webhook.class)) {
            webhookMockedStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Invalid signature"));

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(500, response.getStatusCode());
            assertTrue(response.getBody().contains("Error processing webhook"));
        }
    }

    @Test
    void testHandleRequest_ValidStatusTransition() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "valid-signature");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_123\"}}}");

        try (MockedStatic<Webhook> webhookMockedStatic = mockStatic(Webhook.class)) {
            webhookMockedStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);
            when(mockEvent.getType()).thenReturn("customer.subscription.updated");
            when(mockSubscription.getStatus()).thenReturn("active");

            // Mock existing subscription status
            Map<String, AttributeValue> existingItem = new HashMap<>();
            existingItem.put("status", AttributeValue.builder().s("pending").build());
            when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(existingItem).build());

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(200, response.getStatusCode());
            assertEquals("Subscription updated successfully", response.getBody());
        }
    }

    @Test
    void testHandleRequest_InvalidStatusTransition() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "valid-signature");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_123\"}}}");

        try (MockedStatic<Webhook> webhookMockedStatic = mockStatic(Webhook.class)) {
            webhookMockedStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);
            when(mockEvent.getType()).thenReturn("customer.subscription.updated");
            when(mockSubscription.getStatus()).thenReturn("incomplete_expired");

            // Mock existing subscription status
            Map<String, AttributeValue> existingItem = new HashMap<>();
            existingItem.put("status", AttributeValue.builder().s("active").build());
            when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(existingItem).build());

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(400, response.getStatusCode());
            assertEquals("Invalid subscription status transition", response.getBody());
        }
    }

    @Test
    void testHandleRequest_WithMetadata() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "valid-signature");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"customer.subscription.created\",\"data\":{\"object\":{\"id\":\"sub_123\"}}}");

        try (MockedStatic<Webhook> webhookMockedStatic = mockStatic(Webhook.class)) {
            webhookMockedStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);
            when(mockEvent.getType()).thenReturn("customer.subscription.created");
            when(mockSubscription.getStatus()).thenReturn("active");

            // Mock existing subscription status
            Map<String, AttributeValue> existingItem = new HashMap<>();
            existingItem.put("status", AttributeValue.builder().s("pending").build());
            when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(existingItem).build());

            // Mock subscription metadata
            Map<String, String> metadata = new HashMap<>();
            metadata.put("plan_type", "premium");
            metadata.put("user_tier", "enterprise");
            when(mockSubscription.getMetadata()).thenReturn(metadata);

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(200, response.getStatusCode());
            verify(dynamoDb, times(1)).putItem(any(PutItemRequest.class));
        }
    }

    @Test
    void testHandleRequest_DynamoDBError() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "valid-signature");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_123\"}}}");

        try (MockedStatic<Webhook> webhookMockedStatic = mockStatic(Webhook.class)) {
            webhookMockedStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);
            when(mockEvent.getType()).thenReturn("customer.subscription.updated");
            when(mockSubscription.getStatus()).thenReturn("active");

            // Mock existing subscription status
            Map<String, AttributeValue> existingItem = new HashMap<>();
            existingItem.put("status", AttributeValue.builder().s("pending").build());
            when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(existingItem).build());

            // Mock DynamoDB error
            when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("Table not found").build());

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(500, response.getStatusCode());
            assertTrue(response.getBody().contains("Database configuration error"));
        }
    }

    @Test
    void testHandleRequest_ConcurrentModification() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("Stripe-Signature", "valid-signature");
        request.setHeaders(headers);
        request.setBody("{\"type\":\"customer.subscription.updated\",\"data\":{\"object\":{\"id\":\"sub_123\"}}}");

        try (MockedStatic<Webhook> webhookMockedStatic = mockStatic(Webhook.class)) {
            webhookMockedStatic.when(() -> Webhook.constructEvent(anyString(), anyString(), anyString()))
                .thenReturn(mockEvent);
            when(mockEvent.getType()).thenReturn("customer.subscription.updated");
            when(mockSubscription.getStatus()).thenReturn("active");

            // Mock existing subscription status
            Map<String, AttributeValue> existingItem = new HashMap<>();
            existingItem.put("status", AttributeValue.builder().s("pending").build());
            when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(existingItem).build());

            // Mock concurrent modification error
            when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().message("Concurrent modification").build());

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(409, response.getStatusCode());
            assertTrue(response.getBody().contains("Concurrent modification error"));
        }
    }
} 
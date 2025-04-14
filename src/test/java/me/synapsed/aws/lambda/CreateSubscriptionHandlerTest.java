package me.synapsed.aws.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateSubscriptionHandlerTest {

    @Mock
    private DynamoDbClient dynamoDb;

    @Mock
    private Context context;

    @Mock
    private LambdaLogger logger;

    @Mock
    private Customer mockCustomer;

    @Mock
    private Session mockSession;

    private CreateSubscriptionHandler handler;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(context.getLogger()).thenReturn(logger);
        
        handler = new CreateSubscriptionHandler(dynamoDb,
            "test-subscriptions-table",
            "test-proofs-table",
            "test-stripe-key",
            "price_123,price_456");
        objectMapper = new ObjectMapper();
        
        // Mock DynamoDB responses
        when(dynamoDb.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());
        
        when(dynamoDb.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.emptyList()).build());
    }

    @Test
    void testHandleRequest_MissingFields() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody("{}");

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Missing required fields"));
    }

    @Test
    void testHandleRequest_InvalidPriceId() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "invalid-price-id");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid price ID"));
    }

    @Test
    void testHandleRequest_ValidRequest_Returns200() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        try (MockedStatic<Customer> customerMockedStatic = mockStatic(Customer.class);
             MockedStatic<Session> sessionMockedStatic = mockStatic(Session.class)) {
            
            customerMockedStatic.when(() -> Customer.create(any(CustomerCreateParams.class)))
                .thenReturn(mockCustomer);
            when(mockCustomer.getId()).thenReturn("cus_123");
            
            sessionMockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenReturn(mockSession);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");
            when(mockSession.getCustomer()).thenReturn("cus_123");
            when(mockSession.getSubscription()).thenReturn("sub_123");

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(200, response.getStatusCode());
            assertTrue(response.getBody().contains("checkoutUrl"));
            assertTrue(response.getBody().contains("subscriptionId"));
            
            // Verify DynamoDB calls
            verify(dynamoDb, times(2)).putItem(any(PutItemRequest.class));
        }
    }

    @Test
    void testHandleRequest_WithMetadata() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");
        requestBody.put("metadata_plan", "premium");
        requestBody.put("metadata_tier", "enterprise");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        try (MockedStatic<Customer> customerMockedStatic = mockStatic(Customer.class);
             MockedStatic<Session> sessionMockedStatic = mockStatic(Session.class)) {
            
            customerMockedStatic.when(() -> Customer.create(any(CustomerCreateParams.class)))
                .thenReturn(mockCustomer);
            when(mockCustomer.getId()).thenReturn("cus_123");
            
            sessionMockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenReturn(mockSession);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");
            when(mockSession.getCustomer()).thenReturn("cus_123");
            when(mockSession.getSubscription()).thenReturn("sub_123");

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(200, response.getStatusCode());
            
            // Verify DynamoDB calls with metadata
            verify(dynamoDb, times(2)).putItem(any(PutItemRequest.class));
        }
    }

    @Test
    void testHandleRequest_WithIdempotencyKey() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");
        requestBody.put("idempotencyKey", "test-idempotency-key");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        try (MockedStatic<Customer> customerMockedStatic = mockStatic(Customer.class);
             MockedStatic<Session> sessionMockedStatic = mockStatic(Session.class)) {
            
            customerMockedStatic.when(() -> Customer.create(any(CustomerCreateParams.class)))
                .thenReturn(mockCustomer);
            when(mockCustomer.getId()).thenReturn("cus_123");
            
            sessionMockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenReturn(mockSession);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");
            when(mockSession.getCustomer()).thenReturn("cus_123");
            when(mockSession.getSubscription()).thenReturn("sub_123");

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(200, response.getStatusCode());
            
            // Verify DynamoDB calls with idempotency key
            verify(dynamoDb, times(2)).putItem(any(PutItemRequest.class));
        }
    }

    @Test
    void testHandleRequest_ExistingSubscription() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");
        requestBody.put("idempotencyKey", "test-idempotency-key");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        // Mock existing subscription
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("stripeSubscriptionId", AttributeValue.builder().s("existing-sub-123").build());
        
        when(dynamoDb.query(any(QueryRequest.class)))
            .thenReturn(QueryResponse.builder().items(Collections.singletonList(item)).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("existing-sub-123"));
        assertTrue(response.getBody().contains("existing"));
    }

    @Test
    void testHandleRequest_StripeApiError() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        try (MockedStatic<Session> sessionMockedStatic = mockStatic(Session.class)) {
            sessionMockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenThrow(new InvalidRequestException("API Error", "req_123", "api_error", null, null, null));

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(400, response.getStatusCode());
            assertTrue(response.getBody().contains("Stripe API error"));
        }
    }

    @Test
    void testHandleRequest_ConcurrentCreation() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");
        requestBody.put("idempotencyKey", "test-idempotency-key");

        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        try (MockedStatic<Customer> customerMockedStatic = mockStatic(Customer.class);
             MockedStatic<Session> sessionMockedStatic = mockStatic(Session.class)) {
            
            customerMockedStatic.when(() -> Customer.create(any(CustomerCreateParams.class)))
                .thenReturn(mockCustomer);
            when(mockCustomer.getId()).thenReturn("cus_123");
            
            sessionMockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenReturn(mockSession);
            when(mockSession.getUrl()).thenReturn("https://checkout.stripe.com/test");
            when(mockSession.getCustomer()).thenReturn("cus_123");
            when(mockSession.getSubscription()).thenReturn("sub_123");

            // Mock DynamoDB to throw ConditionalCheckFailedException
            when(dynamoDb.putItem(any(PutItemRequest.class)))
                .thenThrow(ConditionalCheckFailedException.builder().build());

            APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

            assertEquals(409, response.getStatusCode());
            assertTrue(response.getBody().contains("Concurrent subscription creation detected"));
        }
    }
} 
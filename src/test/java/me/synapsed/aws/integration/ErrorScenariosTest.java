package me.synapsed.aws.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.HashMap;
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
import com.stripe.exception.ApiConnectionException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;

import me.synapsed.aws.lambda.CreateSubscriptionHandler;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

public class ErrorScenariosTest {
    @Mock
    private DynamoDbClient dynamoDbClient;
    
    @Mock
    private Context context;
    
    @Mock
    private LambdaLogger logger;
    
    @Mock
    private Session mockSession;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CreateSubscriptionHandler createSubscriptionHandler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(context.getLogger()).thenReturn(logger);
        createSubscriptionHandler = new CreateSubscriptionHandler(
            dynamoDbClient,
            "test-subscriptions-table",
            "test-proofs-table",
            "test-stripe-key",
            "price_123"
        );
    }

    @Test
    void testDatabaseFailure_ResourceNotFound() throws Exception {
        // Create request for subscription creation
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");
        
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));
        
        // Mock Stripe session creation
        Session mockSession = new Session();
        mockSession.setId("sess_123");
        mockSession.setCustomer("cus_123");
        mockSession.setSubscription("sub_123");
        mockSession.setUrl("https://stripe.com/checkout");
        
        try (MockedStatic<Session> mockedStatic = mockStatic(Session.class)) {
            mockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenReturn(mockSession);
            
            // Mock DynamoDB to throw ResourceNotFoundException
            when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("Table not found").build());
            
            // Call the handler
            APIGatewayProxyResponseEvent response = createSubscriptionHandler.handleRequest(request, context);
            
            // Verify response
            assertEquals(500, response.getStatusCode());
            assertTrue(response.getBody().contains("Database configuration error"));
        }
    }

    @Test
    void testDatabaseTimeout() throws Exception {
        // Create request for subscription creation
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");
        
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        // Mock Stripe session creation
        Session mockSession = new Session();
        mockSession.setId("sess_123");
        mockSession.setCustomer("cus_123");
        mockSession.setSubscription("sub_123");
        mockSession.setUrl("https://stripe.com/checkout");
        
        try (MockedStatic<Session> mockedStatic = mockStatic(Session.class)) {
            mockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenReturn(mockSession);
            
            // Mock DynamoDB timeout
            when(dynamoDbClient.putItem(any(PutItemRequest.class)))
                .thenThrow(DynamoDbException.builder().message("Database operation timed out").build());
            
            // Call the handler
            APIGatewayProxyResponseEvent response = createSubscriptionHandler.handleRequest(request, context);
            
            // Verify response
            assertEquals(500, response.getStatusCode());
            assertTrue(response.getBody().contains("Error creating subscription"));
        }
    }

    @Test
    void testStripeApiTimeout() throws Exception {
        // Create request for subscription creation
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "price_123");
        
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        request.setBody(objectMapper.writeValueAsString(requestBody));

        try (MockedStatic<Session> mockedStatic = mockStatic(Session.class)) {
            mockedStatic.when(() -> Session.create(any(SessionCreateParams.class)))
                .thenThrow(new ApiConnectionException("Stripe API request timed out"));
            
            // Call the handler
            APIGatewayProxyResponseEvent response = createSubscriptionHandler.handleRequest(request, context);
            
            // Verify response
            assertEquals(400, response.getStatusCode());
            assertTrue(response.getBody().contains("Stripe API error"));
        }
    }
}
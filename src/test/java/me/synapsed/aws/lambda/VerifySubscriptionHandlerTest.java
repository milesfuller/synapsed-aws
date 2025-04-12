package me.synapsed.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VerifySubscriptionHandlerTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private Context context;

    @Mock
    private LambdaLogger logger;

    private VerifySubscriptionHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(context.getLogger()).thenReturn(logger);
        
        // Set required environment variables
        System.setProperty("SUBSCRIPTIONS_TABLE", "test-subscriptions-table");
        System.setProperty("PROOFS_TABLE", "test-proofs-table");
        System.setProperty("STRIPE_SECRET_KEY", "test-stripe-key");
        
        // Create environment variables map
        Map<String, String> envVars = new HashMap<>();
        envVars.put("SUBSCRIPTIONS_TABLE", "test-subscriptions-table");
        envVars.put("PROOFS_TABLE", "test-proofs-table");
        envVars.put("STRIPE_SECRET_KEY", "test-stripe-key");
        
        handler = new VerifySubscriptionHandler(dynamoDbClient, envVars);
    }

    @Test
    void handleRequest_MissingDID_Returns400() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        request.setHeaders(headers);

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Missing X-DID header"));
    }

    @Test
    void handleRequest_NoSubscription_Returns404() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        request.setHeaders(headers);

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(404, response.getStatusCode());
        assertTrue(response.getBody().contains("No active subscription found"));
    }

    @Test
    void handleRequest_InvalidSubscription_Returns403() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        request.setHeaders(headers);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("status", AttributeValue.builder().s("canceled").build());
        item.put("expiresAt", AttributeValue.builder().s("2024-01-01T00:00:00Z").build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("Subscription is not active"));
    }

    @Test
    void handleRequest_ValidSubscription_Returns200() {
        APIGatewayProxyRequestEvent request = new APIGatewayProxyRequestEvent();
        Map<String, String> headers = new HashMap<>();
        headers.put("X-DID", "test-did");
        request.setHeaders(headers);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("status", AttributeValue.builder().s("active").build());
        item.put("expiresAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis() + 3600000)).build());
        item.put("subscriptionId", AttributeValue.builder().s("sub_123").build());

        when(dynamoDbClient.getItem(any(GetItemRequest.class)))
            .thenReturn(GetItemResponse.builder().item(item).build());

        APIGatewayProxyResponseEvent response = handler.handleRequest(request, context);

        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("sub_123"));
    }
} 
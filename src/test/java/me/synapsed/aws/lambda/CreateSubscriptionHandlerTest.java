package me.synapsed.aws.lambda;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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
import com.stripe.model.Customer;
import com.stripe.model.checkout.Session;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.checkout.SessionCreateParams;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

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
        
        // Set required environment variables
        System.setProperty("SUBSCRIPTIONS_TABLE", "test-subscriptions-table");
        System.setProperty("STRIPE_SECRET_KEY", "test-stripe-key");
        System.setProperty("STRIPE_PRICE_ID", "test-price-id");
        
        handler = new CreateSubscriptionHandler(dynamoDb);
        objectMapper = new ObjectMapper();
        
        // Mock DynamoDB response
        when(dynamoDb.putItem(any(PutItemRequest.class)))
            .thenReturn(PutItemResponse.builder().build());
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
    void testHandleRequest_ValidRequest_Returns200() throws Exception {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("did", "test-did");
        requestBody.put("priceId", "test-price-id");

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
        }
    }
} 
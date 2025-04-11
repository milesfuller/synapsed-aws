package me.synapsed.aws.lambda;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

public class PeerConnectionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String peerConnectionsTable;

    public PeerConnectionHandler() {
        this.dynamoDbClient = DynamoDbClient.builder().build();
        this.objectMapper = new ObjectMapper();
        this.peerConnectionsTable = System.getenv("PEER_CONNECTIONS_TABLE");
    }

    // Constructor for testing
    PeerConnectionHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        this.peerConnectionsTable = System.getenv("PEER_CONNECTIONS_TABLE");
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Extract DID from the request
            String did = input.getHeaders().get("X-DID");
            if (did == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-DID header");
            }

            // Extract subscription proof from the request
            String proof = input.getHeaders().get("X-Subscription-Proof");
            if (proof == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-Subscription-Proof header");
            }

            // Extract action from the request
            Map<String, String> pathParams = input.getPathParameters();
            if (pathParams == null || !pathParams.containsKey("action")) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing action parameter");
            }

            String action = pathParams.get("action");

            // Handle different actions
            switch (action) {
                case "connect":
                    return handleConnect(did, input);
                case "disconnect":
                    return handleDisconnect(did);
                default:
                    return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("Invalid action: " + action);
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing request: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Internal server error");
        }
    }

    private APIGatewayProxyResponseEvent handleConnect(String did, APIGatewayProxyRequestEvent input) {
        try {
            // Generate a unique peer ID
            String peerId = UUID.randomUUID().toString();

            // Store the peer connection information in DynamoDB
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("peerId", AttributeValue.builder().s(peerId).build());
            item.put("did", AttributeValue.builder().s(did).build());
            item.put("endpoint", AttributeValue.builder().s(input.getRequestContext().getIdentity().getSourceIp()).build());
            // Generate a unique connection ID since it's not available in the request context
            item.put("connectionId", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
            item.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());

            PutItemRequest request = PutItemRequest.builder()
                .tableName(peerConnectionsTable)
                .item(item)
                .build();

            dynamoDbClient.putItem(request);

            // Return the peer ID to the client
            Map<String, String> response = new HashMap<>();
            response.put("peerId", peerId);

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error connecting peer: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent handleDisconnect(String did) {
        try {
            // Delete the peer connection information from DynamoDB
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("did", AttributeValue.builder().s(did).build());

            DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName(peerConnectionsTable)
                .key(key)
                .build();

            dynamoDbClient.deleteItem(request);

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("Peer disconnected successfully");
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error disconnecting peer: " + e.getMessage());
        }
    }
} 
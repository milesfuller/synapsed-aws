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
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public class PeerConnectionHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {
    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String peerConnectionsTable;
    private final String subscriptionProofsTable;
    private static final long CONNECTION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes

    public PeerConnectionHandler() {
        this.dynamoDbClient = DynamoDbClient.create();
        this.objectMapper = new ObjectMapper();
        this.peerConnectionsTable = System.getenv("PEER_CONNECTIONS_TABLE");
        this.subscriptionProofsTable = System.getenv("SUBSCRIPTION_PROOFS_TABLE");
    }

    public PeerConnectionHandler(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        this.peerConnectionsTable = System.getenv("PEER_CONNECTIONS_TABLE");
        this.subscriptionProofsTable = System.getenv("SUBSCRIPTION_PROOFS_TABLE");
    }

    public PeerConnectionHandler(DynamoDbClient dynamoDbClient, Map<String, String> env) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = new ObjectMapper();
        this.peerConnectionsTable = env.getOrDefault("PEER_CONNECTIONS_TABLE", System.getenv("PEER_CONNECTIONS_TABLE"));
        this.subscriptionProofsTable = env.getOrDefault("SUBSCRIPTION_PROOFS_TABLE", System.getenv("SUBSCRIPTION_PROOFS_TABLE"));
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Check if headers exist
            Map<String, String> headers = input.getHeaders();
            if (headers == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing request headers");
            }

            // Extract DID from the request
            String did = headers.get("X-DID");
            if (did == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-DID header");
            }

            // Extract subscription proof from the request
            String proof = headers.get("X-Subscription-Proof");
            if (proof == null) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(400)
                    .withBody("Missing X-Subscription-Proof header");
            }

            // Verify the subscription proof
            if (!verifySubscriptionProof(did, proof)) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(403)
                    .withBody("Invalid or expired subscription proof");
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
                    return handleConnect(did, input, context);
                case "disconnect":
                    return handleDisconnect(did, context);
                case "status":
                    return handleStatus(did, context);
                default:
                    return new APIGatewayProxyResponseEvent()
                        .withStatusCode(400)
                        .withBody("Invalid action: " + action);
            }
        } catch (Exception e) {
            context.getLogger().log("Error processing request: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Internal server error: " + e.getMessage());
        }
    }

    private boolean verifySubscriptionProof(String did, String proof) {
        try {
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("did", AttributeValue.builder().s(did).build());
            key.put("proof", AttributeValue.builder().s(proof).build());

            GetItemRequest request = GetItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .key(key)
                .build();

            GetItemResponse response = dynamoDbClient.getItem(request);
            if (!response.hasItem()) {
                return false;
            }

            // Check if the proof is expired
            String expiresAt = response.item().get("expiresAt").s();
            long expirationTime = Long.parseLong(expiresAt);
            return System.currentTimeMillis() < expirationTime;
        } catch (Exception e) {
            return false;
        }
    }

    private APIGatewayProxyResponseEvent handleConnect(String did, APIGatewayProxyRequestEvent input, Context context) {
        try {
            // Check if the peer is already connected
            Map<String, AttributeValue> queryKey = new HashMap<>();
            queryKey.put("did", AttributeValue.builder().s(did).build());

            QueryRequest queryRequest = QueryRequest.builder()
                .tableName(peerConnectionsTable)
                .indexName("DidIndex")
                .keyConditionExpression("did = :did")
                .expressionAttributeValues(Map.of(":did", AttributeValue.builder().s(did).build()))
                .build();

            QueryResponse queryResponse = dynamoDbClient.query(queryRequest);
            
            // If the peer is already connected, return the existing peer ID
            if (queryResponse.hasItems() && !queryResponse.items().isEmpty()) {
                String existingPeerId = queryResponse.items().get(0).get("peerId").s();
                
                // Update the connection timestamp
                Map<String, AttributeValue> updateItem = new HashMap<>(queryResponse.items().get(0));
                updateItem.put("connectedAt", AttributeValue.builder().s(String.valueOf(System.currentTimeMillis())).build());
                
                PutItemRequest updateRequest = PutItemRequest.builder()
                    .tableName(peerConnectionsTable)
                    .item(updateItem)
                    .build();
                
                dynamoDbClient.putItem(updateRequest);
                
                // Return the existing peer ID
                Map<String, String> response = new HashMap<>();
                response.put("peerId", existingPeerId);
                response.put("status", "reconnected");
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(objectMapper.writeValueAsString(response));
            }

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
            item.put("status", AttributeValue.builder().s("connected").build());

            PutItemRequest request = PutItemRequest.builder()
                .tableName(peerConnectionsTable)
                .item(item)
                .build();

            dynamoDbClient.putItem(request);
            
            // Log the connection
            context.getLogger().log("Peer connected: " + did + " with peerId: " + peerId);

            // Return the peer ID to the client
            Map<String, String> response = new HashMap<>();
            response.put("peerId", peerId);
            response.put("status", "connected");

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            context.getLogger().log("Error connecting peer: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error connecting peer: " + e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent handleDisconnect(String did, Context context) {
        try {
            // Find the peer connection by DID
            Map<String, AttributeValue> queryKey = new HashMap<>();
            queryKey.put("did", AttributeValue.builder().s(did).build());

            QueryRequest queryRequest = QueryRequest.builder()
                .tableName(peerConnectionsTable)
                .indexName("DidIndex")
                .keyConditionExpression("did = :did")
                .expressionAttributeValues(Map.of(":did", AttributeValue.builder().s(did).build()))
                .build();

            QueryResponse queryResponse = dynamoDbClient.query(queryRequest);
            
            if (!queryResponse.hasItems() || queryResponse.items().isEmpty()) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(404)
                    .withBody("No active connection found for this DID");
            }
            
            // Delete the peer connection information from DynamoDB
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("peerId", queryResponse.items().get(0).get("peerId"));
            
            DeleteItemRequest request = DeleteItemRequest.builder()
                .tableName(peerConnectionsTable)
                .key(key)
                .build();

            dynamoDbClient.deleteItem(request);
            
            // Log the disconnection
            context.getLogger().log("Peer disconnected: " + did);

            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody("Peer disconnected successfully");
        } catch (Exception e) {
            context.getLogger().log("Error disconnecting peer: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error disconnecting peer: " + e.getMessage());
        }
    }
    
    private APIGatewayProxyResponseEvent handleStatus(String did, Context context) {
        try {
            // Find the peer connection by DID
            Map<String, AttributeValue> queryKey = new HashMap<>();
            queryKey.put("did", AttributeValue.builder().s(did).build());

            QueryRequest queryRequest = QueryRequest.builder()
                .tableName(peerConnectionsTable)
                .indexName("DidIndex")
                .keyConditionExpression("did = :did")
                .expressionAttributeValues(Map.of(":did", AttributeValue.builder().s(did).build()))
                .build();

            QueryResponse queryResponse = dynamoDbClient.query(queryRequest);
            
            if (!queryResponse.hasItems() || queryResponse.items().isEmpty()) {
                Map<String, String> response = new HashMap<>();
                response.put("status", "disconnected");
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(objectMapper.writeValueAsString(response));
            }
            
            // Check if the connection has timed out
            String connectedAtStr = queryResponse.items().get(0).get("connectedAt").s();
            long connectedAt = Long.parseLong(connectedAtStr);
            long now = System.currentTimeMillis();
            
            if (now - connectedAt > CONNECTION_TIMEOUT_MS) {
                // Connection has timed out, delete it
                Map<String, AttributeValue> key = new HashMap<>();
                key.put("peerId", queryResponse.items().get(0).get("peerId"));
                
                DeleteItemRequest deleteRequest = DeleteItemRequest.builder()
                    .tableName(peerConnectionsTable)
                    .key(key)
                    .build();

                dynamoDbClient.deleteItem(deleteRequest);
                
                Map<String, String> response = new HashMap<>();
                response.put("status", "timeout");
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(objectMapper.writeValueAsString(response));
            }
            
            // Connection is active
            Map<String, String> response = new HashMap<>();
            response.put("status", "connected");
            response.put("peerId", queryResponse.items().get(0).get("peerId").s());
            response.put("connectedAt", connectedAtStr);
            
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            context.getLogger().log("Error checking peer status: " + e.getMessage());
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("Error checking peer status: " + e.getMessage());
        }
    }
} 
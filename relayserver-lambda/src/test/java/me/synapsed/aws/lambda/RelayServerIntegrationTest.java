package me.synapsed.aws.lambda;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.services.apigateway.model.*;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

@Testcontainers
public class RelayServerIntegrationTest {
    @Container
    public static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.3.0"))
            .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.SQS);

    private static DynamoDbClient dynamoDbClient;
    private static SqsClient sqsClient;
    private static String subscriptionProofsTable;
    private static String peerConnectionsTable;
    private static String signalingQueueUrl;

    @BeforeAll
    static void setup() {
        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        sqsClient = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        subscriptionProofsTable = "synapsed-subscription-proofs";
        peerConnectionsTable = "synapsed-peer-connections";
        // Create DynamoDB table for subscription proofs
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName(subscriptionProofsTable)
                .keySchema(
                        KeySchemaElement.builder().attributeName("did").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("proof").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("did").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("proof").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(1L).writeCapacityUnits(1L).build())
                .build());
        // Create SQS queue for signaling
        signalingQueueUrl = sqsClient.createQueue(CreateQueueRequest.builder().queueName("signaling-queue").build()).queueUrl();
    }

    @AfterAll
    static void teardown() {
        if (dynamoDbClient != null) dynamoDbClient.close();
        if (sqsClient != null) sqsClient.close();
    }

    @Test
    void testDirectJavaInvocation_validSignalingMessage() throws Exception {
        // Insert a valid proof into DynamoDB
        String did = "did:example:123";
        String proof = "proof-abc";
        long expiresAt = System.currentTimeMillis() + 60000;
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .item(Map.of(
                        "did", AttributeValue.builder().s(did).build(),
                        "proof", AttributeValue.builder().s(proof).build(),
                        "expiresAt", AttributeValue.builder().s(Long.toString(expiresAt)).build()
                ))
                .build());
        // Prepare environment
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        // Prepare API Gateway event
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(
                "X-DID", did,
                "X-Subscription-Proof", proof
        ));
        event.setBody("{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-1\"," +
                "\"sdp\":\"v=0...\"}");
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getBody().contains("Signaling message processed"));
    }

    @Test
    void testDirectJavaInvocation_missingHeaders() {
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Collections.emptyMap());
        event.setBody("{}");
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Missing X-DID header"));
    }

    @Test
    void testDirectJavaInvocation_invalidProof() {
        String did = "did:example:456";
        String proof = "invalid-proof";
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(
                "X-DID", did,
                "X-Subscription-Proof", proof
        ));
        event.setBody("{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-2\"," +
                "\"sdp\":\"v=0...\"}");
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid or expired subscription proof"));
    }

    @Test
    void testDirectJavaInvocation_expiredProof() {
        // Insert an expired proof into DynamoDB
        String did = "did:example:789";
        String proof = "expired-proof";
        long expiresAt = System.currentTimeMillis() - 10000; // expired
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .item(Map.of(
                        "did", AttributeValue.builder().s(did).build(),
                        "proof", AttributeValue.builder().s(proof).build(),
                        "expiresAt", AttributeValue.builder().s(Long.toString(expiresAt)).build()
                ))
                .build());
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(
                "X-DID", did,
                "X-Subscription-Proof", proof
        ));
        event.setBody("{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-3\"," +
                "\"sdp\":\"v=0...\"}");
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(403, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid or expired subscription proof"));
    }

    @Test
    void testDirectJavaInvocation_invalidSignalingType() throws Exception {
        // Insert a valid proof into DynamoDB
        String did = "did:example:321";
        String proof = "proof-def";
        long expiresAt = System.currentTimeMillis() + 60000;
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .item(Map.of(
                        "did", AttributeValue.builder().s(did).build(),
                        "proof", AttributeValue.builder().s(proof).build(),
                        "expiresAt", AttributeValue.builder().s(Long.toString(expiresAt)).build()
                ))
                .build());
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(
                "X-DID", did,
                "X-Subscription-Proof", proof
        ));
        event.setBody("{" +
                "\"type\":\"invalid-type\"," +
                "\"peerId\":\"peer-4\"}");
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid signaling type"));
    }

    @Test
    void testDirectJavaInvocation_missingRequiredFields() throws Exception {
        // Insert a valid proof into DynamoDB
        String did = "did:example:654";
        String proof = "proof-ghi";
        long expiresAt = System.currentTimeMillis() + 60000;
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .item(Map.of(
                        "did", AttributeValue.builder().s(did).build(),
                        "proof", AttributeValue.builder().s(proof).build(),
                        "expiresAt", AttributeValue.builder().s(Long.toString(expiresAt)).build()
                ))
                .build());
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(
                "X-DID", did,
                "X-Subscription-Proof", proof
        ));
        event.setBody("{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-5\"}"); // missing sdp
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Missing required field for offer: sdp"));
    }

    @Test
    void testDirectJavaInvocation_invalidSdp() throws Exception {
        // Insert a valid proof into DynamoDB
        String did = "did:example:987";
        String proof = "proof-jkl";
        long expiresAt = System.currentTimeMillis() + 60000;
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .item(Map.of(
                        "did", AttributeValue.builder().s(did).build(),
                        "proof", AttributeValue.builder().s(proof).build(),
                        "expiresAt", AttributeValue.builder().s(Long.toString(expiresAt)).build()
                ))
                .build());
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(
                "X-DID", did,
                "X-Subscription-Proof", proof
        ));
        event.setBody("{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-6\"," +
                "\"sdp\":\"not-a-valid-sdp\"}");
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid SDP format for offer"));
    }

    @Test
    void testDirectJavaInvocation_invalidIceCandidate() throws Exception {
        // Insert a valid proof into DynamoDB
        String did = "did:example:111";
        String proof = "proof-mno";
        long expiresAt = System.currentTimeMillis() + 60000;
        dynamoDbClient.putItem(PutItemRequest.builder()
                .tableName(subscriptionProofsTable)
                .item(Map.of(
                        "did", AttributeValue.builder().s(did).build(),
                        "proof", AttributeValue.builder().s(proof).build(),
                        "expiresAt", AttributeValue.builder().s(Long.toString(expiresAt)).build()
                ))
                .build());
        Map<String, String> env = new HashMap<>();
        env.put("SUBSCRIPTION_PROOFS_TABLE", subscriptionProofsTable);
        env.put("PEER_CONNECTIONS_TABLE", peerConnectionsTable);
        env.put("SIGNALING_QUEUE_URL", signalingQueueUrl);
        RelayServer handler = new RelayServer(env, dynamoDbClient, sqsClient);
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent event = new com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent();
        event.setHeaders(Map.of(
                "X-DID", did,
                "X-Subscription-Proof", proof
        ));
        event.setBody("{" +
                "\"type\":\"ice-candidate\"," +
                "\"peerId\":\"peer-7\"," +
                "\"candidate\":\"not-a-candidate\"}");
        com.amazonaws.services.lambda.runtime.Context context = new TestLambdaContext();
        com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent response = handler.handleRequest(event, context);
        assertEquals(400, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid ICE candidate format"));
    }

    @Test
    @Disabled("TODO: Implement full LocalStack Lambda+API Gateway integration test")
    void testLambdaDeployedToLocalStack() {
        // This test will:
        // 1. Build the shaded jar
        // 2. Deploy to LocalStack Lambda
        // 3. Set up API Gateway
        // 4. Invoke via HTTP
        // 5. Assert response
    }

    @Test
    @Disabled("Requires shaded jar, Docker, and LocalStack with Lambda/API Gateway. See comments for manual steps.")
    void testFullLocalStackLambdaApiGatewayIntegration() throws Exception {
        String lambdaJarPath = "../target/relayserver-lambda-1.0-SNAPSHOT-shaded.jar";
        String lambdaFunctionName = "relayserver-lambda";
        String handlerClass = "me.synapsed.aws.lambda.RelayServer";
        String runtime = "java21";
        String tableName = "synapsed-subscription-proofs-int";
        String queueName = "signaling-queue-int";
        String apiName = "relayserver-api-int";
        String resourcePath = "relay";
        String did = "did:example:integration";
        String proof = "proof-integration";
        long expiresAt = System.currentTimeMillis() + 60000;
        setupLambdaAndRole(lambdaFunctionName, handlerClass, runtime, lambdaJarPath, "integration");
        setupDynamoDbTable(tableName);
        setupSqsQueue(queueName);
        String invokeUrl = setupApiGateway(lambdaFunctionName, apiName, resourcePath);
        insertProof(setupDynamoDbTable(tableName), tableName, did, proof, expiresAt);
        Map<String, String> headers = Map.of("X-DID", did, "X-Subscription-Proof", proof);
        String requestBody = "{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-int\"," +
                "\"sdp\":\"v=0...\"}";
        sendApiRequestAndAssert(invokeUrl, headers, requestBody, 200, "Signaling message processed");
    }

    @Test
    @Disabled("Requires shaded jar, Docker, and LocalStack with Lambda/API Gateway. See comments for manual steps.")
    void testFullLocalStackLambdaApiGatewayIntegration_expiredProof() throws Exception {
        String lambdaJarPath = "../target/relayserver-lambda-1.0-SNAPSHOT-shaded.jar";
        String lambdaFunctionName = "relayserver-lambda";
        String handlerClass = "me.synapsed.aws.lambda.RelayServer";
        String runtime = "java21";
        String tableName = "synapsed-subscription-proofs-int-expired";
        String queueName = "signaling-queue-int-expired";
        String apiName = "relayserver-api-int-expired";
        String resourcePath = "relay";
        String did = "did:example:expired";
        String proof = "proof-expired";
        long expiresAt = System.currentTimeMillis() - 10000;
        setupLambdaAndRole(lambdaFunctionName, handlerClass, runtime, lambdaJarPath, "expired");
        setupDynamoDbTable(tableName);
        setupSqsQueue(queueName);
        String invokeUrl = setupApiGateway(lambdaFunctionName, apiName, resourcePath);
        insertProof(setupDynamoDbTable(tableName), tableName, did, proof, expiresAt);
        Map<String, String> headers = Map.of("X-DID", did, "X-Subscription-Proof", proof);
        String requestBody = "{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-expired\"," +
                "\"sdp\":\"v=0...\"}";
        sendApiRequestAndAssert(invokeUrl, headers, requestBody, 403, "Invalid or expired subscription proof");
    }

    @Test
    @Disabled("Requires shaded jar, Docker, and LocalStack with Lambda/API Gateway. See comments for manual steps.")
    void testFullLocalStackLambdaApiGatewayIntegration_missingHeaders() throws Exception {
        String lambdaJarPath = "../target/relayserver-lambda-1.0-SNAPSHOT-shaded.jar";
        String lambdaFunctionName = "relayserver-lambda";
        String handlerClass = "me.synapsed.aws.lambda.RelayServer";
        String runtime = "java21";
        String tableName = "synapsed-subscription-proofs-int-missingheaders";
        String queueName = "signaling-queue-int-missingheaders";
        String apiName = "relayserver-api-int-missingheaders";
        String resourcePath = "relay";
        setupLambdaAndRole(lambdaFunctionName, handlerClass, runtime, lambdaJarPath, "missingheaders");
        setupDynamoDbTable(tableName);
        setupSqsQueue(queueName);
        String invokeUrl = setupApiGateway(lambdaFunctionName, apiName, resourcePath);
        String requestBody = "{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-missing\"," +
                "\"sdp\":\"v=0...\"}";
        sendApiRequestAndAssert(invokeUrl, null, requestBody, 400, "Missing X-DID header");
    }

    @Test
    @Disabled("Requires shaded jar, Docker, and LocalStack with Lambda/API Gateway. See comments for manual steps.")
    void testFullLocalStackLambdaApiGatewayIntegration_invalidSignalingType() throws Exception {
        String lambdaJarPath = "../target/relayserver-lambda-1.0-SNAPSHOT-shaded.jar";
        String lambdaFunctionName = "relayserver-lambda";
        String handlerClass = "me.synapsed.aws.lambda.RelayServer";
        String runtime = "java21";
        String tableName = "synapsed-subscription-proofs-int-invalidtype";
        String queueName = "signaling-queue-int-invalidtype";
        String apiName = "relayserver-api-int-invalidtype";
        String resourcePath = "relay";
        String did = "did:example:invalidtype";
        String proof = "proof-invalidtype";
        long expiresAt = System.currentTimeMillis() + 60000;
        setupLambdaAndRole(lambdaFunctionName, handlerClass, runtime, lambdaJarPath, "invalidtype");
        setupDynamoDbTable(tableName);
        setupSqsQueue(queueName);
        String invokeUrl = setupApiGateway(lambdaFunctionName, apiName, resourcePath);
        insertProof(setupDynamoDbTable(tableName), tableName, did, proof, expiresAt);
        Map<String, String> headers = Map.of("X-DID", did, "X-Subscription-Proof", proof);
        String requestBody = "{" +
                "\"type\":\"invalid-type\"," +
                "\"peerId\":\"peer-invalidtype\"}";
        sendApiRequestAndAssert(invokeUrl, headers, requestBody, 400, "Invalid signaling type");
    }

    @Test
    @Disabled("Requires shaded jar, Docker, and LocalStack with Lambda/API Gateway. See comments for manual steps.")
    void testFullLocalStackLambdaApiGatewayIntegration_missingRequiredFields() throws Exception {
        String lambdaJarPath = "../target/relayserver-lambda-1.0-SNAPSHOT-shaded.jar";
        String lambdaFunctionName = "relayserver-lambda";
        String handlerClass = "me.synapsed.aws.lambda.RelayServer";
        String runtime = "java21";
        String tableName = "synapsed-subscription-proofs-int-missingfields";
        String queueName = "signaling-queue-int-missingfields";
        String apiName = "relayserver-api-int-missingfields";
        String resourcePath = "relay";
        String did = "did:example:missingfields";
        String proof = "proof-missingfields";
        long expiresAt = System.currentTimeMillis() + 60000;
        setupLambdaAndRole(lambdaFunctionName, handlerClass, runtime, lambdaJarPath, "missingfields");
        setupDynamoDbTable(tableName);
        setupSqsQueue(queueName);
        String invokeUrl = setupApiGateway(lambdaFunctionName, apiName, resourcePath);
        insertProof(setupDynamoDbTable(tableName), tableName, did, proof, expiresAt);
        Map<String, String> headers = Map.of("X-DID", did, "X-Subscription-Proof", proof);
        String requestBody = "{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-missingfields\"}";
        sendApiRequestAndAssert(invokeUrl, headers, requestBody, 400, "Missing required field for offer: sdp");
    }

    @Test
    @Disabled("Requires shaded jar, Docker, and LocalStack with Lambda/API Gateway. See comments for manual steps.")
    void testFullLocalStackLambdaApiGatewayIntegration_invalidSdpOrIceCandidate() throws Exception {
        String lambdaJarPath = "../target/relayserver-lambda-1.0-SNAPSHOT-shaded.jar";
        String lambdaFunctionName = "relayserver-lambda";
        String handlerClass = "me.synapsed.aws.lambda.RelayServer";
        String runtime = "java21";
        String tableName = "synapsed-subscription-proofs-int-invalidsdp";
        String queueName = "signaling-queue-int-invalidsdp";
        String apiName = "relayserver-api-int-invalidsdp";
        String resourcePath = "relay";
        String did = "did:example:invalidsdp";
        String proof = "proof-invalidsdp";
        long expiresAt = System.currentTimeMillis() + 60000;
        setupLambdaAndRole(lambdaFunctionName, handlerClass, runtime, lambdaJarPath, "invalidsdp");
        setupDynamoDbTable(tableName);
        setupSqsQueue(queueName);
        String invokeUrl = setupApiGateway(lambdaFunctionName, apiName, resourcePath);
        insertProof(setupDynamoDbTable(tableName), tableName, did, proof, expiresAt);
        Map<String, String> headers = Map.of("X-DID", did, "X-Subscription-Proof", proof);
        // Test invalid SDP
        String requestBodySdp = "{" +
                "\"type\":\"offer\"," +
                "\"peerId\":\"peer-invalidsdp\"," +
                "\"sdp\":\"not-a-valid-sdp\"}";
        sendApiRequestAndAssert(invokeUrl, headers, requestBodySdp, 400, "Invalid SDP format for offer");
        // Test invalid ICE candidate
        String requestBodyIce = "{" +
                "\"type\":\"ice-candidate\"," +
                "\"peerId\":\"peer-invalidice\"," +
                "\"candidate\":\"not-a-candidate\"}";
        sendApiRequestAndAssert(invokeUrl, headers, requestBodyIce, 400, "Invalid ICE candidate format");
    }

    // === Helper Methods ===
    private String setupLambdaAndRole(String lambdaFunctionName, String handlerClass, String runtime, String jarPath, String roleSuffix) throws Exception {
        LambdaClient lambdaClient = LambdaClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.LAMBDA))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        IamClient iamClient = IamClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.IAM))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        String assumeRolePolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":[\"lambda.amazonaws.com\"]},\"Action\":[\"sts:AssumeRole\"]}]}";
        CreateRoleResponse roleResponse = iamClient.createRole(CreateRoleRequest.builder()
                .roleName("lambda-execution-role-" + roleSuffix)
                .assumeRolePolicyDocument(assumeRolePolicy)
                .build());
        String roleArn = roleResponse.role().arn();
        byte[] jarBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(jarPath));
        try {
            lambdaClient.createFunction(CreateFunctionRequest.builder()
                    .functionName(lambdaFunctionName)
                    .role(roleArn)
                    .handler(handlerClass)
                    .runtime(runtime)
                    .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(jarBytes)).build())
                    .timeout(30)
                    .memorySize(512)
                    .build());
        } catch (ResourceConflictException e) {
            lambdaClient.updateFunctionCode(UpdateFunctionCodeRequest.builder()
                    .functionName(lambdaFunctionName)
                    .zipFile(SdkBytes.fromByteArray(jarBytes))
                    .build());
        }
        return roleArn;
    }

    private DynamoDbClient setupDynamoDbTable(String tableName) {
        DynamoDbClient ddb = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        ddb.createTable(CreateTableRequest.builder()
                .tableName(tableName)
                .keySchema(
                        KeySchemaElement.builder().attributeName("did").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("proof").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("did").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("proof").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(1L).writeCapacityUnits(1L).build())
                .build());
        return ddb;
    }

    private void setupSqsQueue(String queueName) {
        SqsClient sqs = SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.SQS))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        sqs.createQueue(CreateQueueRequest.builder().queueName(queueName).build()).queueUrl();
    }

    private String setupApiGateway(String lambdaFunctionName, String apiName, String resourcePath) {
        ApiGatewayClient apiGateway = ApiGatewayClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.API_GATEWAY))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        CreateRestApiResponse apiResponse = apiGateway.createRestApi(CreateRestApiRequest.builder().name(apiName).build());
        String restApiId = apiResponse.id();
        GetResourcesResponse resources = apiGateway.getResources(GetResourcesRequest.builder().restApiId(restApiId).build());
        String rootResourceId = resources.items().get(0).id();
        CreateResourceResponse resourceResp = apiGateway.createResource(CreateResourceRequest.builder()
                .restApiId(restApiId)
                .parentId(rootResourceId)
                .pathPart(resourcePath)
                .build());
        String relayResourceId = resourceResp.id();
        apiGateway.putMethod(PutMethodRequest.builder()
                .restApiId(restApiId)
                .resourceId(relayResourceId)
                .httpMethod("POST")
                .authorizationType("NONE")
                .build());
        String lambdaArn = String.format("arn:aws:lambda:%s:000000000000:function:%s", localstack.getRegion(), lambdaFunctionName);
        apiGateway.putIntegration(PutIntegrationRequest.builder()
                .restApiId(restApiId)
                .resourceId(relayResourceId)
                .httpMethod("POST")
                .type(IntegrationType.AWS_PROXY)
                .integrationHttpMethod("POST")
                .uri(String.format("arn:aws:apigateway:%s:lambda:path/2015-03-31/functions/%s/invocations", localstack.getRegion(), lambdaArn))
                .build());
        apiGateway.createDeployment(CreateDeploymentRequest.builder()
                .restApiId(restApiId)
                .stageName("test")
                .build());
        return String.format("http://localhost:%d/restapis/%s/test/_user_request_/%s", localstack.getMappedPort(4566), restApiId, resourcePath);
    }

    private void insertProof(DynamoDbClient ddb, String tableName, String did, String proof, long expiresAt) {
        ddb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        "did", AttributeValue.builder().s(did).build(),
                        "proof", AttributeValue.builder().s(proof).build(),
                        "expiresAt", AttributeValue.builder().s(Long.toString(expiresAt)).build()
                ))
                .build());
    }

    private void sendApiRequestAndAssert(String url, Map<String, String> headers, String body, int expectedStatus, String expectedMessage) throws Exception {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        java.net.http.HttpRequest httpRequest = builder.build();
        java.net.http.HttpResponse<String> httpResponse = httpClient.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, httpResponse.statusCode());
        assertTrue(httpResponse.body().contains(expectedMessage), "Expected message: " + expectedMessage + ", got: " + httpResponse.body());
    }
}

// Minimal Context implementation for testing
class TestLambdaContext implements com.amazonaws.services.lambda.runtime.Context {
    @Override public String getAwsRequestId() { return "test-request-id"; }
    @Override public String getLogGroupName() { return "test-log-group"; }
    @Override public String getLogStreamName() { return "test-log-stream"; }
    @Override public String getFunctionName() { return "test-function"; }
    @Override public String getFunctionVersion() { return "1.0"; }
    @Override public String getInvokedFunctionArn() { return "arn:aws:lambda:us-east-1:000000000000:function:test"; }
    @Override public com.amazonaws.services.lambda.runtime.CognitoIdentity getIdentity() { return null; }
    @Override public com.amazonaws.services.lambda.runtime.ClientContext getClientContext() { return null; }
    @Override public int getRemainingTimeInMillis() { return 30000; }
    @Override public int getMemoryLimitInMB() { return 512; }
    @Override public com.amazonaws.services.lambda.runtime.LambdaLogger getLogger() {
        return new com.amazonaws.services.lambda.runtime.LambdaLogger() {
            @Override public void log(String message) { System.out.println(message); }
            @Override public void log(byte[] message) { System.out.println(new String(message)); }
        };
    }
} 
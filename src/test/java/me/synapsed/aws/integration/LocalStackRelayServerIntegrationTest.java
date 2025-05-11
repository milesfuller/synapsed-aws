package me.synapsed.aws.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest;
import software.amazon.awssdk.services.lambda.model.FunctionCode;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.services.lambda.model.Runtime;
import software.amazon.awssdk.services.lambda.model.CreateFunctionResponse;
import software.amazon.awssdk.services.lambda.model.ResourceNotFoundException;
import java.nio.file.Files;
import java.nio.file.Paths;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import software.amazon.awssdk.core.SdkBytes;

public class LocalStackRelayServerIntegrationTest {
    private static LocalStackContainer localstack;
    private static DynamoDbClient dynamoDbClient;
    private static LambdaClient lambdaClient;

    @BeforeAll
    static void setUp() {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
                .withServices(
                        LocalStackContainer.Service.DYNAMODB,
                        LocalStackContainer.Service.LAMBDA
                );
        localstack.start();

        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())
        );

        dynamoDbClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(credentialsProvider)
                .build();
        lambdaClient = LambdaClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.LAMBDA))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(credentialsProvider)
                .build();

        // Create DynamoDB table for subscription proofs
        dynamoDbClient.createTable(CreateTableRequest.builder()
                .tableName("test-subscription-proofs")
                .keySchema(KeySchemaElement.builder().attributeName("did").keyType(KeyType.HASH).build())
                .attributeDefinitions(AttributeDefinition.builder().attributeName("did").attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(1L).writeCapacityUnits(1L).build())
                .build());
    }

    @AfterAll
    static void tearDown() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    @Test
    void testRelayServerLambdaInvocation() throws Exception {
        // 1. Package path for the fat jar
        String lambdaJarPath = "target/synapsed-aws-1.0-SNAPSHOT-jar-with-dependencies.jar";
        assertTrue(Files.exists(Paths.get(lambdaJarPath)), "Lambda jar does not exist: " + lambdaJarPath);

        // 2. Upload and create the Lambda function in LocalStack
        byte[] jarBytes = Files.readAllBytes(Paths.get(lambdaJarPath));
        String functionName = "RelayServerTestFunction";
        try {
            lambdaClient.deleteFunction(b -> b.functionName(functionName));
        } catch (ResourceNotFoundException ignored) {}
        CreateFunctionResponse createResponse = lambdaClient.createFunction(CreateFunctionRequest.builder()
                .functionName(functionName)
                .runtime(Runtime.JAVA11)
                .role("arn:aws:iam::000000000000:role/lambda-role")
                .handler("me.synapsed.aws.lambda.RelayServer::handleRequest")
                .code(FunctionCode.builder().zipFile(SdkBytes.fromByteArray(jarBytes)).build())
                .memorySize(512)
                .timeout(30)
                .build());
        assertEquals(functionName, createResponse.functionName());

        // 3. Invoke the Lambda with a test signaling message
        String testEvent = "{\"headers\":{\"X-DID\":\"did:example:123\",\"X-Subscription-Proof\":\"proof123\"},\"body\":\"{\\\"type\\\":\\\"offer\\\",\\\"peerId\\\":\\\"peer-abc\\\",\\\"sdp\\\":\\\"test-sdp\\\"}\"}";
        InvokeResponse response = lambdaClient.invoke(InvokeRequest.builder()
                .functionName(functionName)
                .payload(SdkBytes.fromUtf8String(testEvent))
                .build());
        String responsePayload = response.payload().asUtf8String();
        System.out.println("Lambda response: " + responsePayload);
        assertNotNull(responsePayload);
    }
} 
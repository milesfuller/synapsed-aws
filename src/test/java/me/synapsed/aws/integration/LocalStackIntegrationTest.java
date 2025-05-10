package me.synapsed.aws.integration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.apigateway.ApiGatewayClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class LocalStackIntegrationTest {
    private static LocalStackContainer localstack;
    private static DynamoDbClient dynamoDbClient;
    private static S3Client s3Client;
    private static LambdaClient lambdaClient;
    private static ApiGatewayClient apiGatewayClient;

    @SuppressWarnings("resource")
    @BeforeAll
    static void setUp() {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
                .withServices(
                        LocalStackContainer.Service.DYNAMODB,
                        LocalStackContainer.Service.S3,
                        LocalStackContainer.Service.LAMBDA,
                        LocalStackContainer.Service.API_GATEWAY
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
        s3Client = S3Client.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(credentialsProvider)
                .build();
        lambdaClient = LambdaClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.LAMBDA))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(credentialsProvider)
                .build();
        apiGatewayClient = ApiGatewayClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.API_GATEWAY))
                .region(Region.of(localstack.getRegion()))
                .credentialsProvider(credentialsProvider)
                .build();
    }

    @AfterAll
    static void tearDown() {
        if (localstack != null) {
            localstack.stop();
        }
    }

    @Test
    void testLocalStackIsRunning() {
        assertNotNull(dynamoDbClient);
        assertNotNull(s3Client);
        assertNotNull(lambdaClient);
        assertNotNull(apiGatewayClient);
    }

    // Add more integration tests here using the LocalStack endpoints
} 
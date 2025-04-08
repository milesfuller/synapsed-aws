package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import lombok.Getter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.apigateway.AuthorizationType;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.Resource;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.dynamodb.TableProps;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.constructs.Construct;

/**
 * Subscription Stack for Synapsed platform.
 * Implements Stripe integration and subscription management.
 */
@Getter
public class SubscriptionStack extends Stack {
    private final Function createSubscriptionFunction;
    private final Function verifySubscriptionFunction;
    private final Function webhookHandlerFunction;
    private final Table subscriptionsTable;
    private final Table subscriptionProofsTable;
    private final Role subscriptionRole;
    private final RestApi subscriptionApi;

    public SubscriptionStack(final Construct scope, final String id, final StackProps props,
                           final SecurityStack securityStack, final LoggingStack loggingStack) {
        super(scope, id, props);

        // Add subscription-specific tags
        Tags.of(this).add("Subscription", "Enabled");
        Tags.of(this).add("Stripe", "Enabled");
        Tags.of(this).add("API", "Enabled");

        // Create IAM role for subscription services
        this.subscriptionRole = new Role(this, "SubscriptionRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .description("Role for subscription services")
                .build());

        // Add permissions for subscription services
        subscriptionRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "dynamodb:GetItem",
                "dynamodb:PutItem",
                "dynamodb:UpdateItem",
                "dynamodb:DeleteItem",
                "dynamodb:Query",
                "dynamodb:Scan"
            ))
            .resources(Arrays.asList("*"))
            .build());

        // Create DynamoDB tables for subscriptions and proofs
        this.subscriptionsTable = new Table(this, "SubscriptionsTable",
            TableProps.builder()
                .tableName("synapsed-subscriptions")
                .partitionKey(Attribute.builder()
                    .name("did")
                    .type(AttributeType.STRING)
                    .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
                .build());

        this.subscriptionProofsTable = new Table(this, "SubscriptionProofsTable",
            TableProps.builder()
                .tableName("synapsed-subscription-proofs")
                .partitionKey(Attribute.builder()
                    .name("did")
                    .type(AttributeType.STRING)
                    .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
                .build());

        // Add permissions for DynamoDB tables
        subscriptionRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "dynamodb:GetItem",
                "dynamodb:PutItem",
                "dynamodb:UpdateItem",
                "dynamodb:DeleteItem",
                "dynamodb:Query",
                "dynamodb:Scan"
            ))
            .resources(Arrays.asList(
                subscriptionsTable.getTableArn(),
                subscriptionProofsTable.getTableArn()
            ))
            .build());

        // Create Lambda functions for subscription management
        this.createSubscriptionFunction = new Function(this, "CreateSubscriptionFunction",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.CreateSubscriptionHandler::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(subscriptionRole)
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                    "STRIPE_SECRET_KEY", "{{resolve:ssm:/synapsed/stripe/secret-key}}",
                    "SUBSCRIPTIONS_TABLE", subscriptionsTable.getTableName(),
                    "PROOFS_TABLE", subscriptionProofsTable.getTableName(),
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName()
                ))
                .build());

        this.verifySubscriptionFunction = new Function(this, "VerifySubscriptionFunction",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.VerifySubscriptionHandler::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(subscriptionRole)
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                    "STRIPE_SECRET_KEY", "{{resolve:ssm:/synapsed/stripe/secret-key}}",
                    "SUBSCRIPTIONS_TABLE", subscriptionsTable.getTableName(),
                    "PROOFS_TABLE", subscriptionProofsTable.getTableName(),
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName()
                ))
                .build());

        this.webhookHandlerFunction = new Function(this, "WebhookHandlerFunction",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.WebhookHandler::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(subscriptionRole)
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                    "STRIPE_SECRET_KEY", "{{resolve:ssm:/synapsed/stripe/secret-key}}",
                    "STRIPE_WEBHOOK_SECRET", "{{resolve:ssm:/synapsed/stripe/webhook-secret}}",
                    "SUBSCRIPTIONS_TABLE", subscriptionsTable.getTableName(),
                    "PROOFS_TABLE", subscriptionProofsTable.getTableName(),
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName()
                ))
                .build());

        // Create API Gateway
        this.subscriptionApi = new RestApi(this, "SubscriptionApi",
            software.amazon.awscdk.services.apigateway.RestApiProps.builder()
                .restApiName("Synapsed Subscription API")
                .description("API for subscription management")
                .defaultCorsPreflightOptions(software.amazon.awscdk.services.apigateway.CorsOptions.builder()
                    .allowOrigins(software.amazon.awscdk.services.apigateway.Cors.ALL_ORIGINS)
                    .allowMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"))
                    .allowHeaders(Arrays.asList("*"))
                    .allowCredentials(true)
                    .maxAge(Duration.days(1))
                    .build())
                .deployOptions(software.amazon.awscdk.services.apigateway.StageOptions.builder()
                    .stageName("prod")
                    .build())
                .build());

        // Create API resources and methods
        Resource subscriptionResource = subscriptionApi.getRoot().addResource("subscription");
        
        // Create subscription endpoint
        Resource createResource = subscriptionResource.addResource("create");
        createResource.addMethod("POST", 
            new LambdaIntegration(createSubscriptionFunction),
            MethodOptions.builder()
                .authorizationType(AuthorizationType.NONE)
                .build());

        // Verify subscription endpoint
        Resource verifyResource = subscriptionResource.addResource("verify");
        verifyResource.addMethod("POST", 
            new LambdaIntegration(verifySubscriptionFunction),
            MethodOptions.builder()
                .authorizationType(AuthorizationType.NONE)
                .build());

        // Webhook endpoint
        Resource webhookResource = subscriptionResource.addResource("webhook");
        webhookResource.addMethod("POST", 
            new LambdaIntegration(webhookHandlerFunction),
            MethodOptions.builder()
                .authorizationType(AuthorizationType.NONE)
                .build());
    }
} 
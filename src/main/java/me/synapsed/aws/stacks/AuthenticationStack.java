package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import lombok.Getter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.apigateway.AuthorizationType;
import software.amazon.awscdk.services.apigateway.Cors;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.Resource;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.apigateway.RestApiProps;
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
 * Authentication Stack for Synapsed platform.
 * Implements authentication and DID creation functionality.
 */
@Getter
public class AuthenticationStack extends Stack {
    private final RestApi apiGateway;
    private final Function signupFunction;
    private final Function loginFunction;
    private final Function didCreationFunction;
    private final Table usersTable;
    private final Table didsTable;
    private final Role authRole;

    public AuthenticationStack(final Construct scope, final String id, final StackProps props,
                             final SecurityStack securityStack, final LoggingStack loggingStack) {
        super(scope, id, props);

        // Add authentication-specific tags
        Tags.of(this).add("Authentication", "Enabled");
        Tags.of(this).add("DID", "Enabled");
        Tags.of(this).add("API", "Enabled");

        // Create IAM role for authentication services
        this.authRole = new Role(this, "AuthenticationRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .description("Role for authentication services")
                .build());

        // Add permissions for authentication services
        authRole.addToPolicy(PolicyStatement.Builder.create()
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

        // Create DynamoDB tables for users and DIDs
        this.usersTable = new Table(this, "UsersTable",
            TableProps.builder()
                .tableName("synapsed-users")
                .partitionKey(Attribute.builder()
                    .name("userId")
                    .type(AttributeType.STRING)
                    .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
                .build());

        this.didsTable = new Table(this, "DIDsTable",
            TableProps.builder()
                .tableName("synapsed-dids")
                .partitionKey(Attribute.builder()
                    .name("did")
                    .type(AttributeType.STRING)
                    .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
                .build());

        // Add permissions for DynamoDB tables
        authRole.addToPolicy(PolicyStatement.Builder.create()
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
                usersTable.getTableArn(),
                didsTable.getTableArn()
            ))
            .build());

        // Create Lambda functions for authentication
        this.signupFunction = new Function(this, "SignupFunction",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.SignupHandler::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(authRole)
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                    "USERS_TABLE", usersTable.getTableName(),
                    "DIDS_TABLE", didsTable.getTableName(),
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName()
                ))
                .build());

        this.loginFunction = new Function(this, "LoginFunction",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.LoginHandler::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(authRole)
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                    "USERS_TABLE", usersTable.getTableName(),
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName()
                ))
                .build());

        this.didCreationFunction = new Function(this, "DIDCreationFunction",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.DIDCreationHandler::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(authRole)
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .environment(Map.of(
                    "USERS_TABLE", usersTable.getTableName(),
                    "DIDS_TABLE", didsTable.getTableName(),
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName()
                ))
                .build());

        // Create API Gateway
        this.apiGateway = new RestApi(this, "AuthenticationApi",
            RestApiProps.builder()
                .restApiName("Synapsed Authentication API")
                .description("API for authentication and DID creation")
                .defaultCorsPreflightOptions(CorsOptions.builder()
                    .allowOrigins(Cors.ALL_ORIGINS)
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
        Resource authResource = apiGateway.getRoot().addResource("auth");
        
        // Signup endpoint
        Resource signupResource = authResource.addResource("signup");
        signupResource.addMethod("POST", 
            new LambdaIntegration(signupFunction),
            MethodOptions.builder()
                .authorizationType(AuthorizationType.NONE)
                .build());

        // Login endpoint
        Resource loginResource = authResource.addResource("login");
        loginResource.addMethod("POST", 
            new LambdaIntegration(loginFunction),
            MethodOptions.builder()
                .authorizationType(AuthorizationType.NONE)
                .build());

        // DID creation endpoint
        Resource didResource = authResource.addResource("did");
        didResource.addMethod("POST", 
            new LambdaIntegration(didCreationFunction),
            MethodOptions.builder()
                .authorizationType(AuthorizationType.NONE)
                .build());
    }
} 
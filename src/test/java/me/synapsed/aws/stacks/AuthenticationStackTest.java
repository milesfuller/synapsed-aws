package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class AuthenticationStackTest {

    @Test
    void testAuthenticationStack() {
        // Create the app and stack
        App app = new App();
        SecurityStack securityStack = new SecurityStack(app, "TestSecurityStack", null);
        LoggingStack loggingStack = new LoggingStack(app, "TestLoggingStack");
        AuthenticationStack stack = new AuthenticationStack(app, "TestAuthenticationStack", null, securityStack, loggingStack);
        Template template = Template.fromStack(stack);

        // Verify API Gateway
        template.hasResourceProperties("AWS::ApiGateway::RestApi", 
            Match.objectLike(Map.of(
                "Name", "Synapsed Authentication API",
                "Description", "API for authentication and DID creation"
            )));

        // Verify DynamoDB tables
        template.hasResourceProperties("AWS::DynamoDB::Table", 
            Match.objectLike(Map.of(
                "TableName", "synapsed-users",
                "BillingMode", "PAY_PER_REQUEST"
            )));

        template.hasResourceProperties("AWS::DynamoDB::Table", 
            Match.objectLike(Map.of(
                "TableName", "synapsed-dids",
                "BillingMode", "PAY_PER_REQUEST"
            )));

        // Verify Lambda functions
        template.hasResourceProperties("AWS::Lambda::Function", 
            Match.objectLike(Map.of(
                "Handler", "me.synapsed.aws.lambda.SignupHandler::handleRequest",
                "Runtime", "java21",
                "MemorySize", 256,
                "Timeout", 30
            )));

        template.hasResourceProperties("AWS::Lambda::Function", 
            Match.objectLike(Map.of(
                "Handler", "me.synapsed.aws.lambda.LoginHandler::handleRequest",
                "Runtime", "java21",
                "MemorySize", 256,
                "Timeout", 30
            )));

        template.hasResourceProperties("AWS::Lambda::Function", 
            Match.objectLike(Map.of(
                "Handler", "me.synapsed.aws.lambda.DIDCreationHandler::handleRequest",
                "Runtime", "java21",
                "MemorySize", 256,
                "Timeout", 30
            )));

        // Verify IAM role
        template.hasResourceProperties("AWS::IAM::Role", 
            Match.objectLike(Map.of(
                "Description", "Role for authentication services"
            )));

        // Verify IAM role policies
        template.hasResourceProperties("AWS::IAM::Policy", 
            Match.objectLike(Map.of(
                "PolicyDocument", Match.objectLike(Map.of(
                    "Statement", Match.arrayWith(Arrays.asList(
                        Match.objectLike(Map.of(
                            "Effect", "Allow",
                            "Action", Match.arrayWith(Arrays.asList(
                                "logs:CreateLogGroup",
                                "logs:CreateLogStream",
                                "logs:PutLogEvents",
                                "dynamodb:GetItem",
                                "dynamodb:PutItem",
                                "dynamodb:UpdateItem",
                                "dynamodb:DeleteItem",
                                "dynamodb:Query",
                                "dynamodb:Scan"
                            )),
                            "Resource", "*"
                        ))
                    ))
                ))
            )));
    }
} 
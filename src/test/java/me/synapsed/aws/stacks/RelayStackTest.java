package me.synapsed.aws.stacks;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

public class RelayStackTest {
    @Test
    public void testRelayStack() {
        // Create the app and stacks
        App app = new App();
        SecurityStack securityStack = new SecurityStack(app, "SecurityStack", StackProps.builder().build());
        LoggingStack loggingStack = new LoggingStack(app, "LoggingStack");
        RelayStack stack = new RelayStack(app, "RelayStack", StackProps.builder().build(), securityStack, loggingStack);

        // Get the CloudFormation template
        Template template = Template.fromStack(stack);

        // Verify VPC
        template.hasResourceProperties("AWS::EC2::VPC", 
            Match.objectLike(Map.of(
                "EnableDnsHostnames", true,
                "EnableDnsSupport", true
            )));

        // Verify Security Group
        template.hasResourceProperties("AWS::EC2::SecurityGroup", 
            Match.objectLike(Map.of(
                "GroupDescription", "Security group for relay function",
                "SecurityGroupEgress", Match.arrayWith(Arrays.asList(
                    Match.objectLike(Map.of(
                        "CidrIp", "0.0.0.0/0",
                        "Description", "Allow all outbound traffic by default",
                        "IpProtocol", "-1"
                    ))
                ))
            )));

        // Verify Log Group
        template.hasResourceProperties("AWS::Logs::LogGroup", 
            Match.objectLike(Map.of(
                "LogGroupName", "/synapsed/relay",
                "RetentionInDays", 30
            )));

        // Verify IAM Role
        template.hasResourceProperties("AWS::IAM::Role", 
            Match.objectLike(Map.of(
                "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                    "Statement", Match.arrayWith(Arrays.asList(
                        Match.objectLike(Map.of(
                            "Action", "sts:AssumeRole",
                            "Effect", "Allow",
                            "Principal", Match.objectLike(Map.of(
                                "Service", "lambda.amazonaws.com"
                            ))
                        ))
                    ))
                )),
                "Description", "Role for relay function",
                "ManagedPolicyArns", Match.absent()
            )));

        // Verify Lambda Function
        template.hasResourceProperties("AWS::Lambda::Function", 
            Match.objectLike(Map.of(
                "Runtime", "java17",
                "Handler", "me.synapsed.aws.lambda.RelayServer",
                "MemorySize", 1024,
                "Timeout", 30
            )));

        // Verify Lambda Alias
        template.hasResourceProperties("AWS::Lambda::Alias",
            Match.objectLike(Map.of(
                "Name", "prod"
            )));

        // Verify Application Auto Scaling Target
        template.hasResourceProperties("AWS::ApplicationAutoScaling::ScalableTarget",
            Match.objectLike(Map.of(
                "MinCapacity", 1,
                "MaxCapacity", 100,
                "ScalableDimension", "lambda:function:ProvisionedConcurrency",
                "ServiceNamespace", "lambda"
            )));

        // Verify Auto Scaling Policy
        template.hasResourceProperties("AWS::ApplicationAutoScaling::ScalingPolicy",
            Match.objectLike(Map.of(
                "PolicyType", "TargetTrackingScaling",
                "TargetTrackingScalingPolicyConfiguration", Match.objectLike(Map.of(
                    "TargetValue", 0.8,
                    "PredefinedMetricSpecification", Match.objectLike(Map.of(
                        "PredefinedMetricType", "LambdaProvisionedConcurrencyUtilization"
                    ))
                ))
            )));

        // Verify API Gateway
        template.hasResourceProperties("AWS::ApiGateway::RestApi", 
            Match.objectLike(Map.of(
                "Name", "relay-api"
            )));

        // Verify API Gateway Method
        template.hasResourceProperties("AWS::ApiGateway::Method", 
            Match.objectLike(Map.of(
                "HttpMethod", "POST",
                "AuthorizationType", "NONE"
            )));

        // Verify stack outputs
        assertNotNull(stack.getRelayFunction());
        assertNotNull(stack.getRelayApi());
        assertNotNull(stack.getRelayRole());
        assertNotNull(stack.getRelayLogGroup());
    }
}
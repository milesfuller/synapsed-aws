package me.synapsed.aws.stacks;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

import java.util.Arrays;
import java.util.Map;

class SecurityMonitoringStackTest {

    @Test
    void testSecurityMonitoringStack() {
        // Create the app and stack
        App app = new App();
        Stack parentStack = new Stack(app, "TestParentStack");
        LoggingStack loggingStack = new LoggingStack(parentStack, "TestLoggingStack");
        SecurityMonitoringStack stack = new SecurityMonitoringStack(app, "TestSecurityMonitoringStack", null, loggingStack);
        Template template = Template.fromStack(stack);

        // Verify Security Hub
        template.hasResourceProperties("AWS::SecurityHub::Hub", 
            Match.objectLike(Map.of(
                "EnableDefaultStandards", true
            )));

        // Verify GuardDuty Detector
        template.hasResourceProperties("AWS::GuardDuty::Detector", 
            Match.objectLike(Map.of(
                "Enable", true,
                "FindingPublishingFrequency", "FIFTEEN_MINUTES"
            )));

        // Verify Config Recorder
        template.hasResourceProperties("AWS::Config::ConfigurationRecorder", 
            Match.objectLike(Map.of(
                "RecordingGroup", Match.objectLike(Map.of(
                    "AllSupported", true
                ))
            )));

        // Verify IAM Role
        template.hasResourceProperties("AWS::IAM::Role", 
            Match.objectLike(Map.of(
                "Description", "Role for security monitoring services"
            )));

        // Verify IAM Role policies
        template.hasResourceProperties("AWS::IAM::Policy", 
            Match.objectLike(Map.of(
                "PolicyDocument", Match.objectLike(Map.of(
                    "Statement", Match.arrayWith(Arrays.asList(
                        Match.objectLike(Map.of(
                            "Effect", "Allow",
                            "Action", Match.arrayWith(Arrays.asList(
                                "securityhub:*",
                                "guardduty:*",
                                "config:*",
                                "logs:CreateLogGroup",
                                "logs:CreateLogStream",
                                "logs:PutLogEvents"
                            )),
                            "Resource", "*"
                        ))
                    ))
                ))
            )));

        // Verify SNS Topic
        template.hasResource("AWS::SNS::Topic", Match.anyValue());

        // Verify Lambda Function
        template.hasResourceProperties("AWS::Lambda::Function", 
            Match.objectLike(Map.of(
                "Runtime", "java21",
                "Handler", "me.synapsed.aws.lambda.SecurityEventProcessor::handleRequest"
            )));
    }
} 
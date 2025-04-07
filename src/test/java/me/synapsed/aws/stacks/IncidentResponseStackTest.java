package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

public class IncidentResponseStackTest {
    @Test
    public void testIncidentResponseStack() {
        // Create the app and stack
        App app = new App();
        LoggingStack loggingStack = new LoggingStack(app, "LoggingStack");
        SecurityMonitoringStack securityStack = new SecurityMonitoringStack(app, "SecurityStack", null, loggingStack);
        ComplianceStack complianceStack = new ComplianceStack(app, "ComplianceStack", null, loggingStack, securityStack);
        IncidentResponseStack incidentStack = new IncidentResponseStack(app, "IncidentStack", null, loggingStack, securityStack, complianceStack);

        // Prepare the template
        Template template = Template.fromStack(incidentStack);

        // Verify Lambda Error Rate Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "Errors",
            "Namespace", "AWS/Lambda",
            "Statistic", "Sum",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 5,
            "ComparisonOperator", "GreaterThanThreshold",
            "TreatMissingData", "notBreaching"
        )));

        // Verify Lambda Duration Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "Duration",
            "Namespace", "AWS/Lambda",
            "Statistic", "Maximum",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 240000,
            "ComparisonOperator", "GreaterThanThreshold",
            "TreatMissingData", "notBreaching"
        )));

        // Verify S3 Error Rate Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "5xxError",
            "Namespace", "AWS/S3",
            "Statistic", "Sum",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 10,
            "ComparisonOperator", "GreaterThanThreshold",
            "TreatMissingData", "notBreaching"
        )));

        // Verify API Gateway 5XX Error Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "5XXError",
            "Namespace", "AWS/ApiGateway",
            "Statistic", "Sum",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 10,
            "ComparisonOperator", "GreaterThanThreshold",
            "TreatMissingData", "notBreaching"
        )));

        // Verify API Gateway Latency Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "Latency",
            "Namespace", "AWS/ApiGateway",
            "Statistic", "Average",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 1000,
            "ComparisonOperator", "GreaterThanThreshold",
            "TreatMissingData", "notBreaching"
        )));

        // Verify DynamoDB Throttled Requests Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "ThrottledRequests",
            "Namespace", "AWS/DynamoDB",
            "Statistic", "Sum",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 10,
            "ComparisonOperator", "GreaterThanThreshold",
            "TreatMissingData", "notBreaching"
        )));

        // Verify SNS Failed Delivery Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "NumberOfNotificationsFailedToDeliver",
            "Namespace", "AWS/SNS",
            "Statistic", "Sum",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 5,
            "ComparisonOperator", "GreaterThanThreshold",
            "TreatMissingData", "notBreaching"
        )));

        // Verify CloudWatch Logs Ingestion Alarm
        template.hasResourceProperties("AWS::CloudWatch::Alarm", Match.objectLike(Map.of(
            "MetricName", "IncomingBytes",
            "Namespace", "AWS/Logs",
            "Statistic", "Sum",
            "Period", 300,
            "EvaluationPeriods", 2,
            "Threshold", 1000000,
            "ComparisonOperator", "LessThanThreshold",
            "TreatMissingData", "breaching"
        )));

        // Verify EventBridge Rules
        template.hasResourceProperties("AWS::Events::Rule", Match.objectLike(Map.of(
            "EventPattern", Match.objectLike(Map.of(
                "source", Arrays.asList("aws.securityhub"),
                "detail-type", Arrays.asList("Security Hub Findings")
            ))
        )));

        template.hasResourceProperties("AWS::Events::Rule", Match.objectLike(Map.of(
            "EventPattern", Match.objectLike(Map.of(
                "source", Arrays.asList("aws.guardduty"),
                "detail-type", Arrays.asList("GuardDuty Finding")
            ))
        )));

        // Verify IAM Role
        template.hasResourceProperties("AWS::IAM::Role", Map.of(
            "Description", "Role for incident response services",
            "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                "Statement", Arrays.asList(
                    Match.objectLike(Map.of(
                        "Action", "sts:AssumeRole",
                        "Effect", "Allow",
                        "Principal", Map.of(
                            "Service", "lambda.amazonaws.com"
                        )
                    ))
                )
            ))
        ));

        // Verify Lambda Functions
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
            "Runtime", "java21",
            "Handler", "me.synapsed.aws.lambda.IncidentDetector::handleRequest",
            "MemorySize", 256,
            "Timeout", 60
        ));

        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
            "Runtime", "java21",
            "Handler", "me.synapsed.aws.lambda.IncidentResponder::handleRequest",
            "MemorySize", 512,
            "Timeout", 300
        ));

        // Verify Step Functions State Machine
        template.hasResourceProperties("AWS::StepFunctions::StateMachine", Match.objectLike(Map.of(
            "StateMachineName", "IncidentResponseWorkflow"
        )));

        // Verify SNS Topic
        template.hasResourceProperties("AWS::SNS::Topic", Map.of());
    }
} 
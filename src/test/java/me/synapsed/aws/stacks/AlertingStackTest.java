package me.synapsed.aws.stacks;

import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.services.logs.LogGroup;

@ExtendWith(MockitoExtension.class)
public class AlertingStackTest {

    @Mock
    private LoggingStack loggingStack;

    @Mock
    private SecurityMonitoringStack securityStack;

    @Mock
    private IncidentResponseStack incidentStack;

    @Mock
    private ComplianceStack complianceStack;

    @Mock
    private LogGroup auditLogs;

    @Test
    public void testAlertingStack() {
        // Create a new CDK app
        App app = new App();
        
        // Setup mock behavior
        when(loggingStack.getAuditLogs()).thenReturn(auditLogs);
        when(auditLogs.getLogGroupName()).thenReturn("test-log-group");
        
        // Create the stack
        AlertingStack stack = new AlertingStack(app, "TestAlertingStack", StackProps.builder().build(),
            loggingStack, securityStack, incidentStack, complianceStack);
        
        // Get the CloudFormation template
        Template template = Template.fromStack(stack);
        
        // Verify SNS Topics
        template.hasResourceProperties("AWS::SNS::Topic", Match.objectLike(Map.of(
            "TopicName", "critical-alerts"
        )));
        
        template.hasResourceProperties("AWS::SNS::Topic", Match.objectLike(Map.of(
            "TopicName", "warning-alerts"
        )));
        
        template.hasResourceProperties("AWS::SNS::Topic", Match.objectLike(Map.of(
            "TopicName", "info-alerts"
        )));
        
        template.hasResourceProperties("AWS::SNS::Topic", Match.objectLike(Map.of(
            "TopicName", "alert-escalations"
        )));
        
        // Verify S3 Bucket
        template.hasResourceProperties("AWS::S3::Bucket", Match.objectLike(Map.of(
            "BucketName", "synapsed-alerts",
            "VersioningConfiguration", Match.objectLike(Map.of(
                "Status", "Enabled"
            ))
        )));
        
        // Verify Lambda Functions
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
            "Runtime", "java21",
            "Handler", "me.synapsed.aws.lambda.AlertRouter::handleRequest",
            "Timeout", 60,
            "MemorySize", 256
        )));
        
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
            "Runtime", "java21",
            "Handler", "me.synapsed.aws.lambda.NotificationSender::handleRequest",
            "Timeout", 60,
            "MemorySize", 256
        )));
        
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
            "Runtime", "java21",
            "Handler", "me.synapsed.aws.lambda.EscalationManager::handleRequest",
            "Timeout", 60,
            "MemorySize", 256
        )));
        
        // Verify Step Functions State Machine
        template.hasResourceProperties("AWS::StepFunctions::StateMachine", Match.objectLike(Map.of(
            "StateMachineName", "AlertWorkflow"
        )));
        
        // Verify CloudWatch Dashboard
        template.hasResourceProperties("AWS::CloudWatch::Dashboard", Match.objectLike(Map.of(
            "DashboardName", "Synapsed-Alerting"
        )));
        
        // Verify IAM Role
        template.hasResourceProperties("AWS::IAM::Role", Match.objectLike(Map.of(
            "Description", "Role for alerting services",
            "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                "Statement", List.of(
                    Match.objectLike(Map.of(
                        "Action", "sts:AssumeRole",
                        "Effect", "Allow",
                        "Principal", Map.of(
                            "Service", "lambda.amazonaws.com"
                        )
                    ))
                )
            ))
        )));
        
        // Verify Alert Processing Rule
        template.hasResourceProperties("AWS::Events::Rule", Match.objectLike(Map.of(
            "ScheduleExpression", "rate(5 minutes)"
        )));
        
        // Verify the rule has a target
        template.hasResourceProperties("AWS::Events::Rule", Match.objectLike(Map.of(
            "Targets", Match.anyValue()
        )));
    }
} 
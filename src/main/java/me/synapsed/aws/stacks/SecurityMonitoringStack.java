package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import lombok.Getter;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.config.CfnConfigurationRecorder;
import software.amazon.awscdk.services.config.CfnConfigurationRecorderProps;
import software.amazon.awscdk.services.config.CfnDeliveryChannel;
import software.amazon.awscdk.services.config.CfnDeliveryChannelProps;
import software.amazon.awscdk.services.guardduty.CfnDetector;
import software.amazon.awscdk.services.guardduty.CfnDetectorProps;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.securityhub.CfnHub;
import software.amazon.awscdk.services.securityhub.CfnHubProps;
import software.amazon.awscdk.services.sns.Topic;
import software.constructs.Construct;

/**
 * Security Monitoring Stack for Synapsed platform.
 * Implements security monitoring, threat detection, and compliance capabilities.
 */
@Getter
public class SecurityMonitoringStack extends Stack {
    private final CfnHub securityHub;
    private final CfnDetector guardDutyDetector;
    private final CfnConfigurationRecorder configRecorder;
    private final CfnDeliveryChannel configDeliveryChannel;
    private final Role securityMonitoringRole;
    private final Topic securityNotificationsTopic;
    private final Function securityEventProcessor;

    public SecurityMonitoringStack(final Construct scope, final String id, final StackProps props, final LoggingStack loggingStack) {
        super(scope, id, props);

        // Add security-specific and cost allocation tags
        Tags.of(this).add("SecurityLevel", "High");
        Tags.of(this).add("CostCenter", "P2PPlatform");
        Tags.of(this).add("Owner", "PlatformTeam");
        Tags.of(this).add("Environment", System.getenv().getOrDefault("ENVIRONMENT", "dev"));
        Tags.of(this).add("ComplianceFramework", "CIS,PCI-DSS");
        Tags.of(this).add("DataClassification", "Sensitive");

        // Create IAM role for security monitoring
        this.securityMonitoringRole = new Role(this, "SecurityMonitoringRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("securityhub.amazonaws.com"))
                .description("Role for security monitoring services")
                .build());

        // Add permissions for security monitoring
        securityMonitoringRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "securityhub:*",
                "guardduty:*",
                "config:*",
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents"
            ))
            .resources(Arrays.asList("*"))
            .build());

        // Create Security Hub
        this.securityHub = new CfnHub(this, "SecurityHub",
            CfnHubProps.builder()
                .enableDefaultStandards(true)
                .build());

        // Create GuardDuty Detector
        this.guardDutyDetector = new CfnDetector(this, "GuardDutyDetector",
            CfnDetectorProps.builder()
                .enable(true)
                .findingPublishingFrequency("FIFTEEN_MINUTES")
                .build());

        // Create Config Recorder
        this.configRecorder = new CfnConfigurationRecorder(this, "ConfigRecorder",
            CfnConfigurationRecorderProps.builder()
                .recordingGroup(CfnConfigurationRecorder.RecordingGroupProperty.builder()
                    .allSupported(true)
                    .build())
                .roleArn(securityMonitoringRole.getRoleArn())
                .build());

        // Create Config Delivery Channel
        this.configDeliveryChannel = new CfnDeliveryChannel(this, "ConfigDeliveryChannel",
            CfnDeliveryChannelProps.builder()
                .name(configRecorder.getRef())
                .s3BucketName(loggingStack.getLogBucket().getBucketName())
                .build());

        // Create SNS Topic for security notifications
        this.securityNotificationsTopic = new Topic(this, "SecurityNotificationsTopic");

        // Create Lambda function for security event processing
        this.securityEventProcessor = new Function(this, "SecurityEventProcessor",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.SecurityEventProcessor::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(securityMonitoringRole)
                .environment(Map.of(
                    "LOG_GROUP_NAME", loggingStack.getSecurityLogs().getLogGroupName(),
                    "NOTIFICATION_TOPIC_ARN", securityNotificationsTopic.getTopicArn()
                ))
                .build());

        // Add permissions for Lambda to write to CloudWatch Logs
        securityEventProcessor.addToRolePolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents"
            ))
            .resources(Arrays.asList(
                "arn:aws:logs:*:*:log-group:/aws/lambda/*"
            ))
            .build());

        // Add permissions for Lambda to publish to SNS
        securityEventProcessor.addToRolePolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "sns:Publish"
            ))
            .resources(Arrays.asList(
                securityNotificationsTopic.getTopicArn()
            ))
            .build());
    }
} 
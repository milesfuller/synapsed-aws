package me.synapsed.aws.stacks;

import lombok.Getter;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.services.config.CfnConfigRule;
import software.amazon.awscdk.services.config.CfnConfigRuleProps;
import software.amazon.awscdk.services.config.CfnRemediationConfiguration;
import software.amazon.awscdk.services.config.CfnRemediationConfigurationProps;
import software.amazon.awscdk.services.cloudtrail.CfnTrail;
import software.amazon.awscdk.services.cloudtrail.CfnTrailProps;
import software.amazon.awscdk.services.ec2.CfnFlowLog;
import software.amazon.awscdk.services.ec2.CfnFlowLogProps;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.amazon.awscdk.services.sns.Topic;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.Map;

/**
 * Compliance Stack for Synapsed platform.
 * Implements compliance controls, audit logging, reporting, and policy management.
 */
@Getter
public class ComplianceStack extends Stack {
    private final CfnConfigRule[] complianceRules;
    private final CfnTrail auditTrail;
    private final CfnFlowLog vpcFlowLogs;
    private final Role complianceRole;
    private final Function reportGenerator;
    private final Topic complianceNotificationsTopic;
    private final Bucket complianceBucket;

    public ComplianceStack(final Construct scope, final String id, final StackProps props, 
                          final LoggingStack loggingStack, final SecurityMonitoringStack securityStack) {
        super(scope, id, props);

        // Add compliance-specific tags
        Tags.of(this).add("ComplianceLevel", "High");
        Tags.of(this).add("ComplianceFramework", "CIS,PCI-DSS,GDPR");
        Tags.of(this).add("DataRetention", "7Years");

        // Create IAM role for compliance
        this.complianceRole = new Role(this, "ComplianceRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("config.amazonaws.com"))
                .description("Role for compliance services")
                .build());

        // Add permissions for compliance
        complianceRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "config:*",
                "cloudtrail:*",
                "ec2:*",
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "s3:PutObject",
                "s3:GetObject",
                "s3:ListBucket"
            ))
            .resources(Arrays.asList("*"))
            .build());

        // Create S3 bucket for compliance data
        this.complianceBucket = new Bucket(this, "ComplianceBucket",
            BucketProps.builder()
                .versioned(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .lifecycleRules(Arrays.asList(
                    LifecycleRule.builder()
                        .expiration(Duration.days(2555)) // 7 years
                        .build()
                ))
                .build());

        // Create CloudTrail for audit logging
        this.auditTrail = new CfnTrail(this, "ComplianceAuditTrail",
            CfnTrailProps.builder()
                .enableLogFileValidation(true)
                .includeGlobalServiceEvents(true)
                .isMultiRegionTrail(true)
                .s3BucketName(complianceBucket.getBucketName())
                .build());

        // Create VPC Flow Logs
        this.vpcFlowLogs = new CfnFlowLog(this, "VPCFlowLogs",
            CfnFlowLogProps.builder()
                .resourceType("VPC")
                .maxAggregationInterval(60)
                .trafficType("ALL")
                .logDestinationType("s3")
                .logDestination(complianceBucket.getBucketArn())
                .build());

        // Create compliance rules
        this.complianceRules = new CfnConfigRule[] {
            // Encryption rule
            new CfnConfigRule(this, "EncryptionRule",
                CfnConfigRuleProps.builder()
                    .source(CfnConfigRule.SourceProperty.builder()
                        .owner("AWS")
                        .sourceIdentifier("ENCRYPTED_VOLUMES")
                        .build())
                    .build()),
            
            // IAM password policy rule
            new CfnConfigRule(this, "IAMPasswordPolicyRule",
                CfnConfigRuleProps.builder()
                    .source(CfnConfigRule.SourceProperty.builder()
                        .owner("AWS")
                        .sourceIdentifier("IAM_PASSWORD_POLICY")
                        .build())
                    .build()),
            
            // S3 bucket public access rule
            new CfnConfigRule(this, "S3PublicAccessRule",
                CfnConfigRuleProps.builder()
                    .source(CfnConfigRule.SourceProperty.builder()
                        .owner("AWS")
                        .sourceIdentifier("S3_BUCKET_PUBLIC_READ_PROHIBITED")
                        .build())
                    .build())
        };

        // Create remediation configurations
        for (CfnConfigRule rule : complianceRules) {
            new CfnRemediationConfiguration(this, "Remediation-" + rule.getLogicalId(),
                CfnRemediationConfigurationProps.builder()
                    .configRuleName(rule.getConfigRuleName())
                    .targetId("AWS-EnableEncryption")
                    .targetType("SSM_DOCUMENT")
                    .build());
        }

        // Create SNS Topic for compliance notifications
        this.complianceNotificationsTopic = new Topic(this, "ComplianceNotificationsTopic");

        // Create Lambda function for report generation
        this.reportGenerator = new Function(this, "ComplianceReportGenerator",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.ComplianceReportGenerator::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(complianceRole)
                .environment(Map.of(
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName(),
                    "NOTIFICATION_TOPIC_ARN", complianceNotificationsTopic.getTopicArn(),
                    "COMPLIANCE_BUCKET", complianceBucket.getBucketName()
                ))
                .build());

        // Add permissions for Lambda to write to CloudWatch Logs
        reportGenerator.addToRolePolicy(PolicyStatement.Builder.create()
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
        reportGenerator.addToRolePolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "sns:Publish"
            ))
            .resources(Arrays.asList(
                complianceNotificationsTopic.getTopicArn()
            ))
            .build());

        // Add permissions for Lambda to access S3
        reportGenerator.addToRolePolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "s3:PutObject",
                "s3:GetObject",
                "s3:ListBucket"
            ))
            .resources(Arrays.asList(
                complianceBucket.getBucketArn(),
                complianceBucket.getBucketArn() + "/*"
            ))
            .build());
    }
} 
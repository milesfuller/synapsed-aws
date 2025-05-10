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
    private final Role complianceRole;
    private final Function reportGenerator;
    private final Topic complianceNotificationsTopic;
    private final Bucket complianceBucket;

    public ComplianceStack(final Construct scope, final String id, final StackProps props, 
                          final LoggingStack loggingStack, final SecurityMonitoringStack securityStack) {
        super(scope, id, props);

        // Add compliance-specific and cost allocation tags
        Tags.of(this).add("ComplianceLevel", "High");
        Tags.of(this).add("CostCenter", "P2PPlatform");
        Tags.of(this).add("Owner", "PlatformTeam");
        Tags.of(this).add("Environment", System.getenv().getOrDefault("ENVIRONMENT", "dev"));
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
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "s3:PutObject",
                "s3:GetObject",
                "s3:ListBucket"
            ))
            .resources(Arrays.asList("*"))
            .build());

        // Create S3 bucket for compliance data with optimized lifecycle policy
        this.complianceBucket = new Bucket(this, "ComplianceBucket",
            BucketProps.builder()
                .versioned(true)
                .encryption(BucketEncryption.S3_MANAGED)
                .lifecycleRules(Arrays.asList(
                    LifecycleRule.builder()
                        .expiration(Duration.days(365)) // Reduce retention to 1 year to save costs
                        .build()
                ))
                .build());

        // Create CloudTrail for audit logging
        this.auditTrail = new CfnTrail(this, "ComplianceAuditTrail",
            CfnTrailProps.builder()
                .isLogging(true)
                .enableLogFileValidation(false) // Disable log file validation to reduce costs
                .includeGlobalServiceEvents(true)
                .isMultiRegionTrail(false) // Use single region to reduce costs
                .s3BucketName(complianceBucket.getBucketName())
                .build());

        // Create compliance rules - limit to essential rules only
        this.complianceRules = new CfnConfigRule[] {
            // S3 bucket public access rule (most critical for security)
            new CfnConfigRule(this, "S3PublicAccessRule",
                CfnConfigRuleProps.builder()
                    .configRuleName("s3-public-read-prohibited")
                    .source(CfnConfigRule.SourceProperty.builder()
                        .owner("AWS")
                        .sourceIdentifier("S3_BUCKET_PUBLIC_READ_PROHIBITED")
                        .build())
                    .build())
        };

        // Create remediation configurations only for critical rules
        for (CfnConfigRule rule : complianceRules) {
            new CfnRemediationConfiguration(this, "Remediation-" + rule.getConfigRuleName(),
                CfnRemediationConfigurationProps.builder()
                    .configRuleName(rule.getConfigRuleName())
                    .targetId("AWS-EnableEncryption")
                    .targetType("SSM_DOCUMENT")
                    .build());
        }

        // Create SNS Topic for compliance notifications
        this.complianceNotificationsTopic = new Topic(this, "ComplianceNotificationsTopic");

        // Create Lambda function for report generation with minimal resources
        this.reportGenerator = new Function(this, "ComplianceReportGenerator",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.ComplianceReportGenerator::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(complianceRole)
                .memorySize(128) // Reduce memory to minimum required
                .timeout(Duration.seconds(30)) // Reduce timeout to minimum required
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

        // Review: Compliance S3 bucket retention is set to 1 year. Ensure this aligns with privacy and regulatory requirements.
        // NOTE: Use Athena or OpenSearch to analyze compliance/audit logs for privacy violations or anomalous access patterns.
    }
} 
package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

public class ComplianceStackTest {
    @Test
    public void testComplianceStack() {
        // Create the app and stack
        App app = new App();
        LoggingStack loggingStack = new LoggingStack(app, "LoggingStack");
        SecurityMonitoringStack securityStack = new SecurityMonitoringStack(app, "SecurityStack", null, loggingStack);
        ComplianceStack complianceStack = new ComplianceStack(app, "ComplianceStack", null, loggingStack, securityStack);

        // Prepare the template
        Template template = Template.fromStack(complianceStack);

        // Verify CloudTrail
        template.hasResourceProperties("AWS::CloudTrail::Trail", Map.of(
            "IsLogging", true,
            "EnableLogFileValidation", false,
            "IncludeGlobalServiceEvents", true,
            "IsMultiRegionTrail", false
        ));

        // Verify Config Rules - only S3 public access rule
        template.hasResourceProperties("AWS::Config::ConfigRule", Match.objectLike(Map.of(
            "ConfigRuleName", "s3-public-read-prohibited",
            "Source", Match.objectLike(Map.of(
                "Owner", "AWS",
                "SourceIdentifier", "S3_BUCKET_PUBLIC_READ_PROHIBITED"
            ))
        )));

        // Verify IAM Role
        template.hasResourceProperties("AWS::IAM::Role", Map.of(
            "Description", "Role for compliance services",
            "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                "Statement", Arrays.asList(
                    Match.objectLike(Map.of(
                        "Action", "sts:AssumeRole",
                        "Effect", "Allow",
                        "Principal", Map.of(
                            "Service", "config.amazonaws.com"
                        )
                    ))
                )
            ))
        ));

        // Verify S3 Bucket with 1-year retention
        template.hasResourceProperties("AWS::S3::Bucket", Map.of(
            "VersioningConfiguration", Match.objectLike(Map.of(
                "Status", "Enabled"
            )),
            "BucketEncryption", Match.objectLike(Map.of(
                "ServerSideEncryptionConfiguration", Match.arrayWith(Arrays.asList(
                    Match.objectLike(Map.of(
                        "ServerSideEncryptionByDefault", Match.objectLike(Map.of(
                            "SSEAlgorithm", "AES256"
                        ))
                    ))
                ))
            ))
        ));

        // Verify Lambda Function with minimal resources
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
            "Runtime", "java21",
            "Handler", "me.synapsed.aws.lambda.ComplianceReportGenerator::handleRequest",
            "MemorySize", 128,
            "Timeout", 30
        ));
    }
} 
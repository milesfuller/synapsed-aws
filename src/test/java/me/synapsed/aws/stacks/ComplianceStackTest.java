package me.synapsed.aws.stacks;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.sns.Topic;

import java.util.Map;
import java.util.Arrays;

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
            "EnableLogFileValidation", true,
            "IncludeGlobalServiceEvents", true,
            "IsMultiRegionTrail", true
        ));

        // Verify VPC Flow Logs
        template.hasResourceProperties("AWS::Logs::FlowLog", Map.of(
            "ResourceType", "VPC",
            "TrafficType", "ALL",
            "LogDestinationType", "s3"
        ));

        // Verify Config Rules
        template.hasResourceProperties("AWS::Config::ConfigRule", Map.of(
            "Source", Match.objectLike(Map.of(
                "Owner", "AWS",
                "SourceIdentifier", "ENCRYPTED_VOLUMES"
            ))
        ));

        // Verify IAM Role
        template.hasResourceProperties("AWS::IAM::Role", Map.of(
            "Description", "Role for compliance services"
        ));

        // Verify S3 Bucket
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

        // Verify SNS Topic
        template.hasResourceProperties("AWS::SNS::Topic", Map.of());

        // Verify Lambda Function
        template.hasResourceProperties("AWS::Lambda::Function", Map.of(
            "Runtime", "java21",
            "Handler", "me.synapsed.aws.lambda.ComplianceReportGenerator::handleRequest"
        ));
    }
} 
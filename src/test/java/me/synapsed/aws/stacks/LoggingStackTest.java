package me.synapsed.aws.stacks;

import lombok.val;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

import java.util.List;
import java.util.Map;

class LoggingStackTest {

    @Test
    void testLoggingStack() {
        // Create the stack
        val app = new App();
        val parentStack = new Stack(app, "TestParentStack");
        val stack = new LoggingStack(parentStack, "TestLoggingStack");
        val template = Template.fromStack(stack);

        // Test KMS key
        template.hasResourceProperties("AWS::KMS::Key", Match.objectLike(Map.of(
            "Description", "KMS key for log encryption",
            "EnableKeyRotation", true
        )));

        // Test S3 bucket
        template.hasResourceProperties("AWS::S3::Bucket", Match.objectLike(Map.of(
            "BucketEncryption", Match.objectLike(Map.of(
                "ServerSideEncryptionConfiguration", Match.arrayWith(List.of(Map.of(
                    "ServerSideEncryptionByDefault", Match.objectLike(Map.of(
                        "SSEAlgorithm", "AES256"
                    ))
                )))
            )),
            "LifecycleConfiguration", Match.objectLike(Map.of(
                "Rules", Match.arrayWith(List.of(
                    Match.objectLike(Map.of(
                        "Transitions", Match.arrayWith(List.of(Map.of(
                            "StorageClass", "GLACIER",
                            "TransitionInDays", 90
                        )))
                    )),
                    Match.objectLike(Map.of(
                        "Transitions", Match.arrayWith(List.of(Map.of(
                            "StorageClass", "DEEP_ARCHIVE",
                            "TransitionInDays", 365
                        )))
                    )),
                    Match.objectLike(Map.of(
                        "ExpirationInDays", 730
                    ))
                ))
            ))
        )));

        // Test Firehose role
        template.hasResourceProperties("AWS::IAM::Role", Match.objectLike(Map.of(
            "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                "Statement", Match.arrayWith(List.of(Map.of(
                    "Action", "sts:AssumeRole",
                    "Effect", "Allow",
                    "Principal", Match.objectLike(Map.of(
                        "Service", "firehose.amazonaws.com"
                    ))
                )))
            ))
        )));

        // Test Firehose delivery stream
        template.hasResourceProperties("AWS::KinesisFirehose::DeliveryStream", Match.objectLike(Map.of(
            "DeliveryStreamType", "DirectPut",
            "DeliveryStreamEncryptionConfigurationInput", Match.objectLike(Map.of(
                "KeyType", "CUSTOMER_MANAGED_CMK",
                "KeyARN", Match.anyValue()
            )),
            "S3DestinationConfiguration", Match.objectLike(Map.of(
                "BufferingHints", Match.objectLike(Map.of(
                    "IntervalInSeconds", 300,
                    "SizeInMBs", 5
                )),
                "EncryptionConfiguration", Match.objectLike(Map.of(
                    "KMSEncryptionConfig", Match.objectLike(Map.of(
                        "AWSKMSKeyARN", Match.anyValue()
                    ))
                ))
            ))
        )));

        // Test CloudWatch log groups
        template.hasResourceProperties("AWS::Logs::LogGroup", Match.objectLike(Map.of(
            "RetentionInDays", 30
        )));

        template.hasResourceProperties("AWS::Logs::LogGroup", Match.objectLike(Map.of(
            "RetentionInDays", 365
        )));

        template.hasResourceProperties("AWS::Logs::LogGroup", Match.objectLike(Map.of(
            "RetentionInDays", 731
        )));

        template.hasResourceProperties("AWS::Logs::LogGroup", Match.objectLike(Map.of(
            "RetentionInDays", 30
        )));

        // Test Athena workgroup
        template.hasResourceProperties("AWS::Athena::WorkGroup", Match.objectLike(Map.of(
            "Name", "synapsed-logs",
            "WorkGroupConfiguration", Match.objectLike(Map.of(
                "ResultConfiguration", Match.objectLike(Map.of(
                    "OutputLocation", Match.anyValue()
                ))
            ))
        )));

        // Test OpenSearch domain
        template.hasResourceProperties("AWS::OpenSearchService::Domain", Match.objectLike(Map.of(
            "EngineVersion", "OpenSearch_2.3",
            "ClusterConfig", Match.objectLike(Map.of(
                "InstanceCount", 2,
                "InstanceType", "t3.small.search"
            )),
            "EncryptionAtRestOptions", Match.objectLike(Map.of(
                "Enabled", true
            ))
        )));
    }
} 
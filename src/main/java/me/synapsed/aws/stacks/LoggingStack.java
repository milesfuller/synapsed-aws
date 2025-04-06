package me.synapsed.aws.stacks;

import java.util.Arrays;

import lombok.Getter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.athena.CfnWorkGroup;
import software.amazon.awscdk.services.athena.CfnWorkGroupProps;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.kinesisfirehose.CfnDeliveryStream;
import software.amazon.awscdk.services.kinesisfirehose.CfnDeliveryStreamProps;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.kms.KeyProps;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.opensearchservice.CapacityConfig;
import software.amazon.awscdk.services.opensearchservice.Domain;
import software.amazon.awscdk.services.opensearchservice.DomainProps;
import software.amazon.awscdk.services.opensearchservice.EncryptionAtRestOptions;
import software.amazon.awscdk.services.opensearchservice.EngineVersion;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketEncryption;
import software.amazon.awscdk.services.s3.BucketProps;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.amazon.awscdk.services.s3.StorageClass;
import software.amazon.awscdk.services.s3.Transition;
import software.constructs.Construct;

@Getter
public class LoggingStack extends Stack {
    private final Key logEncryptionKey;
    private final Bucket logBucket;
    private final Role firehoseRole;
    private final CfnDeliveryStream logDeliveryStream;
    private final LogGroup applicationLogs;
    private final LogGroup securityLogs;
    private final LogGroup auditLogs;
    private final LogGroup performanceLogs;
    private final CfnWorkGroup athenaWorkgroup;
    private final Domain openSearchDomain;

    public LoggingStack(final Construct scope, final String id) {
        super(scope, id);

        // Create KMS key for encryption
        this.logEncryptionKey = new Key(this, "LogEncryptionKey",
            KeyProps.builder()
                .description("KMS key for log encryption")
                .enableKeyRotation(true)
                .build()
        );

        // Create S3 bucket for log storage with lifecycle rules
        this.logBucket = new Bucket(this, "LogBucket",
            BucketProps.builder()
                .encryption(BucketEncryption.S3_MANAGED)
                .lifecycleRules(Arrays.asList(
                    LifecycleRule.builder()
                        .transitions(Arrays.asList(
                            Transition.builder()
                                .storageClass(StorageClass.GLACIER)
                                .transitionAfter(Duration.days(90))
                                .build()
                        ))
                        .build(),
                    LifecycleRule.builder()
                        .transitions(Arrays.asList(
                            Transition.builder()
                                .storageClass(StorageClass.DEEP_ARCHIVE)
                                .transitionAfter(Duration.days(365))
                                .build()
                        ))
                        .build(),
                    LifecycleRule.builder()
                        .expiration(Duration.days(730))
                        .build()
                ))
                .build()
        );

        // Create IAM role for Firehose
        this.firehoseRole = new Role(this, "FirehoseRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("firehose.amazonaws.com"))
                .build()
        );

        // Grant Firehose permissions
        logBucket.grantReadWrite(firehoseRole);
        logEncryptionKey.grantEncryptDecrypt(firehoseRole);

        // Create Firehose delivery stream
        this.logDeliveryStream = new CfnDeliveryStream(this, "LogDeliveryStream",
            CfnDeliveryStreamProps.builder()
                .deliveryStreamType("DirectPut")
                .deliveryStreamEncryptionConfigurationInput(CfnDeliveryStream.DeliveryStreamEncryptionConfigurationInputProperty.builder()
                    .keyType("CUSTOMER_MANAGED_CMK")
                    .keyArn(logEncryptionKey.getKeyArn())
                    .build())
                .s3DestinationConfiguration(CfnDeliveryStream.S3DestinationConfigurationProperty.builder()
                    .bucketArn(logBucket.getBucketArn())
                    .roleArn(firehoseRole.getRoleArn())
                    .bufferingHints(CfnDeliveryStream.BufferingHintsProperty.builder()
                        .intervalInSeconds(300)
                        .sizeInMBs(5)
                        .build())
                    .encryptionConfiguration(CfnDeliveryStream.EncryptionConfigurationProperty.builder()
                        .kmsEncryptionConfig(CfnDeliveryStream.KMSEncryptionConfigProperty.builder()
                            .awskmsKeyArn(logEncryptionKey.getKeyArn())
                            .build())
                        .build())
                    .build())
                .build()
        );

        // Create CloudWatch log groups with appropriate retention
        this.applicationLogs = new LogGroup(this, "ApplicationLogs",
            LogGroupProps.builder()
                .retention(RetentionDays.ONE_MONTH)
                .build()
        );

        this.securityLogs = new LogGroup(this, "SecurityLogs",
            LogGroupProps.builder()
                .retention(RetentionDays.ONE_YEAR)
                .build()
        );

        this.auditLogs = new LogGroup(this, "AuditLogs",
            LogGroupProps.builder()
                .retention(RetentionDays.TWO_YEARS)
                .build()
        );

        this.performanceLogs = new LogGroup(this, "PerformanceLogs",
            LogGroupProps.builder()
                .retention(RetentionDays.ONE_MONTH)
                .build()
        );

        // Create Athena workgroup for log analysis
        this.athenaWorkgroup = new CfnWorkGroup(this, "LogAnalysisWorkgroup",
            CfnWorkGroupProps.builder()
                .name("synapsed-logs")
                .workGroupConfiguration(CfnWorkGroup.WorkGroupConfigurationProperty.builder()
                    .resultConfiguration(CfnWorkGroup.ResultConfigurationProperty.builder()
                        .outputLocation("s3://" + logBucket.getBucketName() + "/athena-results/")
                        .build())
                    .build())
                .build()
        );

        // Create OpenSearch domain for log search
        this.openSearchDomain = new Domain(this, "LogSearchDomain",
            DomainProps.builder()
                .version(EngineVersion.OPENSEARCH_2_3)
                .capacity(CapacityConfig.builder()
                    .dataNodeInstanceType("t3.small.search")
                    .dataNodes(2)
                    .build())
                .encryptionAtRest(EncryptionAtRestOptions.builder()
                    .enabled(true)
                    .kmsKey(logEncryptionKey)
                    .build())
                .build()
        );
    }
} 
package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import lombok.Getter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.AlarmProps;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.events.EventPattern;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.RuleProps;
import software.amazon.awscdk.services.events.targets.LambdaFunction;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineProps;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvoke;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvokeProps;
import software.constructs.Construct;

/**
 * Incident Response Stack for Synapsed platform.
 * Implements incident detection, automated response, and recovery procedures.
 */
@Getter
public class IncidentResponseStack extends Stack {
    private final Alarm[] incidentAlarms;
    private final Rule[] incidentRules;
    private final Role incidentRole;
    private final Function incidentDetector;
    private final Function incidentResponder;
    private final StateMachine incidentWorkflow;
    private final Topic incidentNotificationsTopic;

    public IncidentResponseStack(final Construct scope, final String id, final StackProps props,
                               final LoggingStack loggingStack, final SecurityMonitoringStack securityStack,
                               final ComplianceStack complianceStack) {
        super(scope, id, props);

        // Add incident response-specific and cost allocation tags
        Tags.of(this).add("IncidentResponse", "Enabled");
        Tags.of(this).add("CostCenter", "P2PPlatform");
        Tags.of(this).add("Owner", "PlatformTeam");
        Tags.of(this).add("Environment", System.getenv().getOrDefault("ENVIRONMENT", "dev"));
        Tags.of(this).add("AutomatedResponse", "True");
        Tags.of(this).add("RecoveryProcedures", "Automated");

        // Create IAM role for incident response
        this.incidentRole = new Role(this, "IncidentResponseRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .description("Role for incident response services")
                .build());

        // Add permissions for incident response
        incidentRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "cloudwatch:GetMetricData",
                "cloudwatch:GetMetricStatistics",
                "securityhub:GetFindings",
                "config:GetComplianceDetailsByConfigRule",
                "s3:GetObject",
                "s3:PutObject",
                "sns:Publish"
            ))
            .resources(Arrays.asList("*"))
            .build());

        // Create SNS Topic for incident notifications
        this.incidentNotificationsTopic = new Topic(this, "IncidentNotificationsTopic");

        // Create Lambda function for incident detection
        this.incidentDetector = new Function(this, "IncidentDetector",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.IncidentDetector::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(incidentRole)
                .memorySize(256)
                .timeout(Duration.seconds(60))
                .environment(Map.of(
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName(),
                    "NOTIFICATION_TOPIC_ARN", incidentNotificationsTopic.getTopicArn()
                ))
                .build());

        // Create Lambda function for incident response
        this.incidentResponder = new Function(this, "IncidentResponder",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.IncidentResponder::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(incidentRole)
                .memorySize(512)
                .timeout(Duration.seconds(300))
                .environment(Map.of(
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName(),
                    "NOTIFICATION_TOPIC_ARN", incidentNotificationsTopic.getTopicArn()
                ))
                .build());

        // Create Step Functions workflow for incident response
        LambdaInvoke detectIncident = new LambdaInvoke(this, "DetectIncident",
            LambdaInvokeProps.builder()
                .lambdaFunction(incidentDetector)
                .build());

        LambdaInvoke respondToIncident = new LambdaInvoke(this, "RespondToIncident",
            LambdaInvokeProps.builder()
                .lambdaFunction(incidentResponder)
                .build());

        detectIncident.next(respondToIncident);

        this.incidentWorkflow = new StateMachine(this, "IncidentResponseWorkflow",
            StateMachineProps.builder()
                .stateMachineName("IncidentResponseWorkflow")
                .definitionBody(software.amazon.awscdk.services.stepfunctions.DefinitionBody.fromChainable(detectIncident))
                .timeout(Duration.minutes(30))
                .build());

        // Create CloudWatch Alarms for incident detection
        this.incidentAlarms = new Alarm[] {
            // High Lambda Error Rate Alarm
            new Alarm(this, "HighLambdaErrorRateAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/Lambda")
                        .metricName("Errors")
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(5)
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.NOT_BREACHING)
                    .alarmDescription("Lambda function error rate is above threshold")
                    .build()),

            // High Lambda Duration Alarm
            new Alarm(this, "HighLambdaDurationAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/Lambda")
                        .metricName("Duration")
                        .statistic("Maximum")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(240000) // 4 minutes in milliseconds
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.NOT_BREACHING)
                    .alarmDescription("Lambda function duration is approaching timeout")
                    .build()),

            // S3 Error Rate Alarm
            new Alarm(this, "S3ErrorRateAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/S3")
                        .metricName("5xxError")
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(10)
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.NOT_BREACHING)
                    .alarmDescription("S3 bucket error rate is above threshold")
                    .build()),
                    
            // API Gateway 5XX Error Alarm
            new Alarm(this, "ApiGateway5XXErrorAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/ApiGateway")
                        .metricName("5XXError")
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(10)
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.NOT_BREACHING)
                    .alarmDescription("API Gateway 5XX error rate is above threshold")
                    .build()),
                    
            // API Gateway Latency Alarm
            new Alarm(this, "ApiGatewayLatencyAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/ApiGateway")
                        .metricName("Latency")
                        .statistic("Average")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(1000) // 1 second in milliseconds
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.NOT_BREACHING)
                    .alarmDescription("API Gateway latency is above threshold")
                    .build()),
                    
            // DynamoDB Throttled Requests Alarm
            new Alarm(this, "DynamoDBThrottledRequestsAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/DynamoDB")
                        .metricName("ThrottledRequests")
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(10)
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.NOT_BREACHING)
                    .alarmDescription("DynamoDB throttled requests are above threshold")
                    .build()),
                    
            // SNS Failed Delivery Alarm
            new Alarm(this, "SNSFailedDeliveryAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/SNS")
                        .metricName("NumberOfNotificationsFailedToDeliver")
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(5)
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.NOT_BREACHING)
                    .alarmDescription("SNS failed message deliveries are above threshold")
                    .build()),
                    
            // CloudWatch Logs Ingestion Alarm
            new Alarm(this, "CloudWatchLogsIngestionAlarm",
                AlarmProps.builder()
                    .metric(Metric.Builder.create()
                        .namespace("AWS/Logs")
                        .metricName("IncomingBytes")
                        .statistic("Sum")
                        .period(Duration.minutes(5))
                        .build())
                    .threshold(1000000) // 1MB in bytes
                    .evaluationPeriods(2)
                    .comparisonOperator(ComparisonOperator.LESS_THAN_THRESHOLD)
                    .treatMissingData(TreatMissingData.BREACHING)
                    .alarmDescription("CloudWatch Logs ingestion is below threshold, possible logging issues")
                    .build())
        };

        // Create EventBridge Rules for incident detection
        this.incidentRules = new Rule[] {
            // Security Hub Findings Rule
            new Rule(this, "SecurityHubFindingsRule",
                RuleProps.builder()
                    .eventPattern(EventPattern.builder()
                        .source(Arrays.asList("aws.securityhub"))
                        .detailType(Arrays.asList("Security Hub Findings"))
                        .build())
                    .targets(Arrays.asList(new LambdaFunction(incidentDetector)))
                    .build()),

            // GuardDuty Findings Rule
            new Rule(this, "GuardDutyFindingsRule",
                RuleProps.builder()
                    .eventPattern(EventPattern.builder()
                        .source(Arrays.asList("aws.guardduty"))
                        .detailType(Arrays.asList("GuardDuty Finding"))
                        .build())
                    .targets(Arrays.asList(new LambdaFunction(incidentDetector)))
                    .build())
        };
    }
} 
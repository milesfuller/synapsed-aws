package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.cloudwatch.Dashboard;
import software.amazon.awscdk.services.cloudwatch.DashboardProps;
import software.amazon.awscdk.services.cloudwatch.GraphWidget;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.events.Rule;
import software.amazon.awscdk.services.events.RuleProps;
import software.amazon.awscdk.services.events.Schedule;
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
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.LifecycleRule;
import software.amazon.awscdk.services.sns.Topic;
import software.amazon.awscdk.services.sns.TopicProps;
import software.amazon.awscdk.services.sns.subscriptions.EmailSubscription;
import software.amazon.awscdk.services.stepfunctions.Chain;
import software.amazon.awscdk.services.stepfunctions.Choice;
import software.amazon.awscdk.services.stepfunctions.Condition;
import software.amazon.awscdk.services.stepfunctions.DefinitionBody;
import software.amazon.awscdk.services.stepfunctions.Pass;
import software.amazon.awscdk.services.stepfunctions.PassProps;
import software.amazon.awscdk.services.stepfunctions.Result;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachineProps;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvoke;
import software.amazon.awscdk.services.stepfunctions.tasks.LambdaInvokeProps;
import software.constructs.Construct;

/**
 * Alerting Stack for Synapsed platform.
 * Implements alert management, notification delivery, escalation management, and alert analytics.
 */
@Getter
public class AlertingStack extends Stack {
    private final Topic criticalAlertsTopic;
    private final Topic warningAlertsTopic;
    private final Topic infoAlertsTopic;
    private final Topic escalationTopic;
    private final Function alertRouter;
    private final Function notificationSender;
    private final Function escalationManager;
    private final StateMachine alertWorkflow;
    private final Dashboard alertDashboard;
    private final Role alertRole;
    private final Bucket alertsBucket;
    private final Rule alertProcessingRule;

    public AlertingStack(final Construct scope, final String id, final StackProps props,
                        final LoggingStack loggingStack, final SecurityMonitoringStack securityStack,
                        final IncidentResponseStack incidentStack, final ComplianceStack complianceStack) {
        super(scope, id, props);

        // Add alerting-specific and cost allocation tags
        Tags.of(this).add("Alerting", "Enabled");
        Tags.of(this).add("CostCenter", "P2PPlatform");
        Tags.of(this).add("Owner", "PlatformTeam");
        Tags.of(this).add("Environment", System.getenv().getOrDefault("ENVIRONMENT", "dev"));
        Tags.of(this).add("NotificationDelivery", "MultiChannel");
        Tags.of(this).add("EscalationManagement", "Automated");

        // Create IAM role for alerting services
        this.alertRole = new Role(this, "AlertingRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .description("Role for alerting services")
                .build());

        // Add permissions for alerting services
        alertRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "cloudwatch:GetMetricData",
                "cloudwatch:GetMetricStatistics",
                "sns:Publish",
                "ses:SendEmail",
                "ses:SendRawEmail",
                "chatbot:DescribeSlackWorkspaces",
                "chatbot:DescribeSlackChannels",
                "chatbot:PostMessage"
            ))
            .resources(Arrays.asList("*"))
            .build());

        // Create SNS Topics for different alert severities
        this.criticalAlertsTopic = new Topic(this, "CriticalAlertsTopic",
            TopicProps.builder()
                .displayName("Critical Alerts")
                .topicName("critical-alerts")
                .build());

        this.warningAlertsTopic = new Topic(this, "WarningAlertsTopic",
            TopicProps.builder()
                .displayName("Warning Alerts")
                .topicName("warning-alerts")
                .build());

        this.infoAlertsTopic = new Topic(this, "InfoAlertsTopic",
            TopicProps.builder()
                .displayName("Info Alerts")
                .topicName("info-alerts")
                .build());

        this.escalationTopic = new Topic(this, "EscalationTopic",
            TopicProps.builder()
                .displayName("Alert Escalations")
                .topicName("alert-escalations")
                .build());

        // Create S3 bucket for storing alerts
        this.alertsBucket = Bucket.Builder.create(this, "AlertsBucket")
            .bucketName("synapsed-alerts")
            .versioned(true)
            .lifecycleRules(List.of(
                LifecycleRule.builder()
                    .expiration(Duration.days(90))
                    .build()
            ))
            .build();

        // Create Lambda function for alert routing
        this.alertRouter = new Function(this, "AlertRouter",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.AlertRouter::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(alertRole)
                .memorySize(256)
                .timeout(Duration.seconds(60))
                .environment(Map.of(
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName(),
                    "CRITICAL_TOPIC_ARN", criticalAlertsTopic.getTopicArn(),
                    "WARNING_TOPIC_ARN", warningAlertsTopic.getTopicArn(),
                    "INFO_TOPIC_ARN", infoAlertsTopic.getTopicArn()
                ))
                .build());

        // Create Lambda function for notification sending
        this.notificationSender = new Function(this, "NotificationSender",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.NotificationSender::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(alertRole)
                .memorySize(256)
                .timeout(Duration.seconds(60))
                .environment(Map.of(
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName(),
                    "SLACK_WORKSPACE_ID", System.getenv().getOrDefault("SLACK_WORKSPACE_ID", ""),
                    "SLACK_CHANNEL_ID", System.getenv().getOrDefault("SLACK_CHANNEL_ID", ""),
                    "TEAMS_WEBHOOK_URL", System.getenv().getOrDefault("TEAMS_WEBHOOK_URL", ""),
                    "PAGERDUTY_API_KEY", System.getenv().getOrDefault("PAGERDUTY_API_KEY", ""),
                    "PAGERDUTY_SERVICE_ID", System.getenv().getOrDefault("PAGERDUTY_SERVICE_ID", ""),
                    "SENDER_EMAIL", "alerts@synapsed.com"
                ))
                .build());

        // Create Lambda function for escalation management
        this.escalationManager = new Function(this, "EscalationManager",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_21)
                .handler("me.synapsed.aws.lambda.EscalationManager::handleRequest")
                .code(Code.fromAsset("src/main/java/me/synapsed/aws/lambda"))
                .role(alertRole)
                .memorySize(256)
                .timeout(Duration.seconds(60))
                .environment(Map.of(
                    "LOG_GROUP_NAME", loggingStack.getAuditLogs().getLogGroupName(),
                    "CRITICAL_TOPIC_ARN", criticalAlertsTopic.getTopicArn(),
                    "WARNING_TOPIC_ARN", warningAlertsTopic.getTopicArn(),
                    "ESCALATION_TOPIC_ARN", escalationTopic.getTopicArn()
                ))
                .build());

        // Create Step Functions workflow for alert processing
        Pass startState = new Pass(this, "StartState", PassProps.builder()
            .result(Result.fromObject(Map.of("status", "STARTED")))
            .build());

        LambdaInvoke routeAlert = new LambdaInvoke(this, "RouteAlert",
            LambdaInvokeProps.builder()
                .lambdaFunction(alertRouter)
                .build());

        LambdaInvoke sendNotification = new LambdaInvoke(this, "SendNotification",
            LambdaInvokeProps.builder()
                .lambdaFunction(notificationSender)
                .build());

        LambdaInvoke checkEscalation = new LambdaInvoke(this, "CheckEscalation",
            LambdaInvokeProps.builder()
                .lambdaFunction(escalationManager)
                .build());

        Pass endState = new Pass(this, "EndState", PassProps.builder()
            .result(Result.fromObject(Map.of("status", "COMPLETED")))
            .build());

        // Define the workflow
        Choice shouldEscalate = new Choice(this, "ShouldEscalate")
            .when(Condition.isPresent("$.escalationNeeded"), checkEscalation)
            .otherwise(endState);

        // Create the state machine
        this.alertWorkflow = new StateMachine(this, "AlertWorkflow",
            StateMachineProps.builder()
                .stateMachineName("AlertWorkflow")
                .definitionBody(DefinitionBody.fromChainable(Chain.start(startState)
                    .next(routeAlert)
                    .next(sendNotification)
                    .next(shouldEscalate)))
                .timeout(Duration.minutes(5))
                .build());

        // Create EventBridge Rule for scheduled alert processing
        this.alertProcessingRule = new Rule(this, "AlertProcessingRule",
            RuleProps.builder()
                .schedule(Schedule.rate(Duration.minutes(5)))
                .targets(Arrays.asList(new LambdaFunction(alertRouter)))
                .build());

        // Create CloudWatch dashboard for alert analytics
        this.alertDashboard = new Dashboard(this, "AlertingDashboard",
            DashboardProps.builder()
                .dashboardName("Synapsed-Alerting")
                .widgets(Arrays.asList(Arrays.asList(
                    GraphWidget.Builder.create()
                        .title("Alerts by Severity")
                        .left(List.of(
                            Metric.Builder.create()
                                .namespace("Synapsed/Alerts")
                                .metricName("AlertsBySeverity")
                                .statistic("Sum")
                                .period(Duration.minutes(5))
                                .dimensionsMap(Map.of("Severity", "CRITICAL"))
                                .build(),
                            Metric.Builder.create()
                                .namespace("Synapsed/Alerts")
                                .metricName("AlertsBySeverity")
                                .statistic("Sum")
                                .period(Duration.minutes(5))
                                .dimensionsMap(Map.of("Severity", "WARNING"))
                                .build(),
                            Metric.Builder.create()
                                .namespace("Synapsed/Alerts")
                                .metricName("AlertsBySeverity")
                                .statistic("Sum")
                                .period(Duration.minutes(5))
                                .dimensionsMap(Map.of("Severity", "INFO"))
                                .build()
                        ))
                        .build()
                )))
                .build()
        );

        // Subscribe to SNS topics for notification delivery
        // Note: In a real implementation, you would add actual email addresses, phone numbers, etc.
        // For this example, we're just setting up the structure
        criticalAlertsTopic.addSubscription(new EmailSubscription("security-team@synapsed.com"));
        warningAlertsTopic.addSubscription(new EmailSubscription("security-team@synapsed.com"));
        infoAlertsTopic.addSubscription(new EmailSubscription("security-team@synapsed.com"));
        escalationTopic.addSubscription(new EmailSubscription("security-managers@synapsed.com"));
    }
} 
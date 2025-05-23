package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import lombok.Getter;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.MethodOptions;
import software.amazon.awscdk.services.apigateway.MethodResponse;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.backup.BackupPlan;
import software.amazon.awscdk.services.backup.BackupPlanRule;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.ec2.FlowLog;
import software.amazon.awscdk.services.ec2.FlowLogDestination;
import software.amazon.awscdk.services.ec2.GatewayVpcEndpointAwsService;
import software.amazon.awscdk.services.ec2.GatewayVpcEndpointOptions;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcProps;
import software.amazon.awscdk.services.events.Schedule;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.kms.KeyProps;
import software.amazon.awscdk.services.lambda.Alias;
import software.amazon.awscdk.services.lambda.AutoScalingOptions;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.IScalableFunctionAttribute;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.UtilizationScalingOptions;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.LogGroupProps;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.constructs.Construct;

/**
 * Relay Stack for Synapsed platform.
 * Implements optional relay servers for P2P communication.
 */
@Getter
public class RelayStack extends Stack {
    private final Function relayFunction;
    private final RestApi relayApi;
    private final Table peerConnectionsTable;
    private final Role relayRole;
    private final LogGroup relayLogGroup;

    public RelayStack(final Construct scope, final String id, final StackProps props,
                     final SecurityStack securityStack, final LoggingStack loggingStack) {
        super(scope, id, props);

        // Add relay-specific and cost allocation tags
        Tags.of(this).add("Relay", "Enabled");
        Tags.of(this).add("P2P", "Enabled");
        Tags.of(this).add("WebRTC", "Enabled");
        Tags.of(this).add("CostCenter", "P2PPlatform");
        Tags.of(this).add("Owner", "PlatformTeam");
        Tags.of(this).add("Environment", System.getenv().getOrDefault("ENVIRONMENT", "dev"));

        // Create a KMS key for encryption (can be shared across resources)
        Key relayKmsKey = new Key(this, "RelayKmsKey", KeyProps.builder()
            .enableKeyRotation(true)
            .description("KMS key for RelayStack resources")
            .build());

        // Create a VPC for the relay servers
        Vpc vpc = new Vpc(this, "RelayVpc",
            VpcProps.builder()
                .maxAzs(1)
                .natGateways(0)
                .enableDnsHostnames(true)
                .enableDnsSupport(true)
                .build());

        // Add VPC endpoints for DynamoDB and S3
        vpc.addGatewayEndpoint("DynamoDbEndpoint", GatewayVpcEndpointOptions.builder()
            .service(GatewayVpcEndpointAwsService.DYNAMODB)
            .build());
        vpc.addGatewayEndpoint("S3Endpoint", GatewayVpcEndpointOptions.builder()
            .service(GatewayVpcEndpointAwsService.S3)
            .build());

        // Create security group for the relay function
        SecurityGroup relaySecurityGroup = SecurityGroup.Builder.create(this, "RelaySecurityGroup")
            .vpc(vpc)
            .description("Security group for relay function")
            .allowAllOutbound(true)
            .build();

        // Allow WebRTC traffic
        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.tcp(80),
            "Allow HTTP traffic"
        );

        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.tcp(443),
            "Allow HTTPS traffic"
        );

        // Allow WebRTC ports
        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.udp(3478),
            "Allow STUN traffic"
        );

        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.udp(5349),
            "Allow TURN traffic"
        );

        // Allow additional WebRTC ports for NAT traversal
        // Instead of a range, we'll add rules for common WebRTC ports
        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.udp(49152),
            "Allow WebRTC dynamic port 49152"
        );
        
        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.udp(49153),
            "Allow WebRTC dynamic port 49153"
        );
        
        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.udp(49154),
            "Allow WebRTC dynamic port 49154"
        );
        
        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.udp(49155),
            "Allow WebRTC dynamic port 49155"
        );
        
        relaySecurityGroup.addIngressRule(
            Peer.anyIpv4(),
            Port.udp(49156),
            "Allow WebRTC dynamic port 49156"
        );

        // Create a log group for the relay servers (encrypted)
        this.relayLogGroup = new LogGroup(this, "RelayLogGroup",
            LogGroupProps.builder()
                .logGroupName("/synapsed/relay")
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.DESTROY)
                .encryptionKey(relayKmsKey)
                .build());

        // Create DynamoDB table for peer connections (encrypted)
        this.peerConnectionsTable = Table.Builder.create(this, "PeerConnectionsTable")
            .tableName("synapsed-peer-connections")
            .partitionKey(Attribute.builder()
                .name("peerId")
                .type(AttributeType.STRING)
                .build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(software.amazon.awscdk.RemovalPolicy.DESTROY)
            .encryptionKey(relayKmsKey)
            .build();

        // Create an IAM role for the relay function
        this.relayRole = new Role(this, "RelayRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .description("Role for relay function")
                .build());

        // Restrict IAM permissions to only the specific log group and DynamoDB table
        relayRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents"
            ))
            .resources(Arrays.asList(
                relayLogGroup.getLogGroupArn()
            ))
            .build());
        relayRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "dynamodb:GetItem",
                "dynamodb:PutItem",
                "dynamodb:DeleteItem",
                "dynamodb:UpdateItem"
            ))
            .resources(Arrays.asList(
                peerConnectionsTable.getTableArn()
            ))
            .build());

        // Create the relay Lambda function
        this.relayFunction = new Function(this, "RelayFunction",
            FunctionProps.builder()
                .runtime(Runtime.JAVA_17)
                .handler("me.synapsed.aws.lambda.RelayServer")
                .code(software.amazon.awscdk.services.lambda.Code.fromAsset("target/classes"))
                .role(relayRole)
                .timeout(Duration.seconds(30))
                .memorySize(1024)
                .vpc(vpc)
                .securityGroups(Arrays.asList(relaySecurityGroup))
                .environment(Map.of(
                    "SUBSCRIPTION_PROOFS_TABLE", "synapsed-subscription-proofs",
                    "PEER_CONNECTIONS_TABLE", peerConnectionsTable.getTableName(),
                    "STUN_SERVER", "stun:stun.l.google.com:19302",
                    "TURN_SERVER", "turn:your-turn-server.com:3478",
                    "TURN_USERNAME", "your-turn-username", // Replace with SSM/SecretsManager reference
                    "TURN_CREDENTIAL", "your-turn-credential" // Replace with SSM/SecretsManager reference
                ))
                .build());

        // Create the REST API Gateway
        this.relayApi = RestApi.Builder.create(this, "RelayApi")
            .restApiName("relay-api")
            .description("API for WebRTC signaling")
            .build();

        // Create the signaling resource
        var signalingResource = relayApi.getRoot().addResource("signaling");

        // Add POST method to the signaling resource
        signalingResource.addMethod("POST",
            new LambdaIntegration(relayFunction),
            MethodOptions.builder()
                .methodResponses(Arrays.asList(
                    MethodResponse.builder()
                        .statusCode("200")
                        .build(),
                    MethodResponse.builder()
                        .statusCode("400")
                        .build(),
                    MethodResponse.builder()
                        .statusCode("403")
                        .build(),
                    MethodResponse.builder()
                        .statusCode("500")
                        .build()
                ))
                .build());

        // Add auto-scaling policy for concurrent executions
        Alias prodAlias = Alias.Builder.create(this, "ProdAlias")
            .aliasName("prod")
            .version(relayFunction.getCurrentVersion())
            .build();

        IScalableFunctionAttribute scalableTarget = prodAlias.addAutoScaling(AutoScalingOptions.builder()
            .minCapacity(1)
            .maxCapacity(100)
            .build());

        scalableTarget.scaleOnUtilization(UtilizationScalingOptions.builder()
            .utilizationTarget(0.8)
            .build());

        // Add outputs
        new CfnOutput(this, "RelayFunctionArn", CfnOutputProps.builder()
            .value(relayFunction.getFunctionArn())
            .description("Relay Function ARN")
            .build());

        new CfnOutput(this, "RelayApiUrl", CfnOutputProps.builder()
            .value(relayApi.getUrl())
            .description("Relay API URL")
            .build());

        new CfnOutput(this, "PeerConnectionsTableName", CfnOutputProps.builder()
            .value(peerConnectionsTable.getTableName())
            .description("Peer Connections Table Name")
            .build());

        // Create backup plan with selection
        BackupPlan.Builder.create(this, "RelayBackupPlan")
            .backupPlanRules(Arrays.asList(
                BackupPlanRule.Builder.create()
                    .completionWindow(Duration.hours(2))
                    .startWindow(Duration.hours(1))
                    .scheduleExpression(Schedule.expression("cron(0 5 ? * * *)")) // Daily backup at 5 AM UTC
                    .deleteAfter(Duration.days(30)) // Keep backups for 30 days
                    .build(),
                BackupPlanRule.Builder.create()
                    .completionWindow(Duration.hours(2))
                    .startWindow(Duration.hours(1))
                    .scheduleExpression(Schedule.expression("cron(0 5 ? * SUN *)")) // Weekly backup on Sundays
                    .deleteAfter(Duration.days(90)) // Keep weekly backups for 90 days
                    .build()
            ))
            .build()
            .addSelection("RelayResources", 
                software.amazon.awscdk.services.backup.BackupSelectionOptions.builder()
                    .resources(Arrays.asList(
                        software.amazon.awscdk.services.backup.BackupResource.fromDynamoDbTable(peerConnectionsTable),
                        software.amazon.awscdk.services.backup.BackupResource.fromArn(relayFunction.getFunctionArn())
                    ))
                    .build());

        // Enable VPC Flow Logs for auditability and privacy monitoring
        FlowLog.Builder.create(this, "RelayVpcFlowLog")
            .resourceType(software.amazon.awscdk.services.ec2.FlowLogResourceType.fromVpc(vpc))
            .destination(FlowLogDestination.toS3(loggingStack.getLogBucket()))
            .build();
        // NOTE: Use Athena or OpenSearch to analyze VPC Flow Logs for privacy violations or anomalous access patterns.

        // NOTE: For AWS Budgets and Cost Anomaly Detection, create a separate stack or script at the account level.
        // These services are not typically provisioned per-stack in CDK, but can be managed via CloudFormation or the AWS Console.
    }
} 
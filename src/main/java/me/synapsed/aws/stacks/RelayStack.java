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
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcProps;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.Runtime;
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

        // Add relay-specific tags
        Tags.of(this).add("Relay", "Enabled");
        Tags.of(this).add("P2P", "Enabled");
        Tags.of(this).add("WebRTC", "Enabled");

        // Create a VPC for the relay servers
        Vpc vpc = new Vpc(this, "RelayVpc",
            VpcProps.builder()
                .maxAzs(1)
                .natGateways(0)
                .enableDnsHostnames(true)
                .enableDnsSupport(true)
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

        // Create a log group for the relay servers
        this.relayLogGroup = new LogGroup(this, "RelayLogGroup",
            LogGroupProps.builder()
                .logGroupName("/synapsed/relay")
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.DESTROY)
                .build());

        // Create DynamoDB table for peer connections
        this.peerConnectionsTable = Table.Builder.create(this, "PeerConnectionsTable")
            .tableName("synapsed-peer-connections")
            .partitionKey(Attribute.builder()
                .name("peerId")
                .type(AttributeType.STRING)
                .build())
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .removalPolicy(software.amazon.awscdk.RemovalPolicy.DESTROY)
            .build();

        // Create an IAM role for the relay function
        this.relayRole = new Role(this, "RelayRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .description("Role for relay function")
                .build());

        // Add permissions for the relay function
        relayRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "dynamodb:GetItem",
                "dynamodb:PutItem",
                "dynamodb:DeleteItem",
                "dynamodb:UpdateItem"
            ))
            .resources(Arrays.asList(
                peerConnectionsTable.getTableArn(),
                "arn:aws:logs:*:*:*"
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
                    "PEER_CONNECTIONS_TABLE", peerConnectionsTable.getTableName()
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
    }
} 
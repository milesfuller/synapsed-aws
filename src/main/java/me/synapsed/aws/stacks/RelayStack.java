package me.synapsed.aws.stacks;

import java.util.Arrays;

import lombok.Getter;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ec2.VpcProps;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ClusterProps;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.FargateService;
import software.amazon.awscdk.services.ecs.FargateServiceProps;
import software.amazon.awscdk.services.ecs.FargateTaskDefinition;
import software.amazon.awscdk.services.ecs.FargateTaskDefinitionProps;
import software.amazon.awscdk.services.ecs.LogDrivers;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancer;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationLoadBalancerProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationProtocol;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroup;
import software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationTargetGroupProps;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.elasticloadbalancingv2.TargetType;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
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
    private final FargateService relayService;
    private final ApplicationLoadBalancer relayLoadBalancer;
    private final ApplicationTargetGroup relayTargetGroup;
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

        // Create a security group for the relay servers
        SecurityGroup relaySecurityGroup = new SecurityGroup(this, "RelaySecurityGroup",
            software.amazon.awscdk.services.ec2.SecurityGroupProps.builder()
                .vpc(vpc)
                .description("Security group for relay servers")
                .allowAllOutbound(true)
                .build());

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

        // Create an IAM role for the relay servers
        this.relayRole = new Role(this, "RelayRole",
            RoleProps.builder()
                .assumedBy(new ServicePrincipal("ecs-tasks.amazonaws.com"))
                .description("Role for relay servers")
                .build());

        // Add permissions for the relay servers
        relayRole.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents",
                "ecr:GetAuthorizationToken",
                "ecr:BatchCheckLayerAvailability",
                "ecr:GetDownloadUrlForLayer",
                "ecr:BatchGetImage"
            ))
            .resources(Arrays.asList("*"))
            .build());

        // Create an ECS cluster for the relay servers
        Cluster cluster = new Cluster(this, "RelayCluster",
            ClusterProps.builder()
                .vpc(vpc)
                .build());

        // Create a task definition for the relay servers
        FargateTaskDefinition taskDefinition = new FargateTaskDefinition(this, "RelayTaskDefinition",
            FargateTaskDefinitionProps.builder()
                .memoryLimitMiB(1024)
                .cpu(512)
                .taskRole(relayRole)
                .executionRole(relayRole)
                .build());

        // Create container definition properties
        software.amazon.awscdk.services.ecs.ContainerDefinitionOptions containerProps = software.amazon.awscdk.services.ecs.ContainerDefinitionOptions.builder()
            .image(ContainerImage.fromRegistry("synapsed/relay-server:latest"))
            .logging(LogDrivers.awsLogs(software.amazon.awscdk.services.ecs.AwsLogDriverProps.builder()
                .logGroup(relayLogGroup)
                .streamPrefix("relay")
                .build()))
            .essential(true)
            .memoryLimitMiB(1024)
            .cpu(512)
            .portMappings(Arrays.asList(
                software.amazon.awscdk.services.ecs.PortMapping.builder()
                    .containerPort(80)
                    .hostPort(80)
                    .protocol(software.amazon.awscdk.services.ecs.Protocol.TCP)
                    .build(),
                software.amazon.awscdk.services.ecs.PortMapping.builder()
                    .containerPort(443)
                    .hostPort(443)
                    .protocol(software.amazon.awscdk.services.ecs.Protocol.TCP)
                    .build(),
                software.amazon.awscdk.services.ecs.PortMapping.builder()
                    .containerPort(3478)
                    .hostPort(3478)
                    .protocol(software.amazon.awscdk.services.ecs.Protocol.UDP)
                    .build(),
                software.amazon.awscdk.services.ecs.PortMapping.builder()
                    .containerPort(5349)
                    .hostPort(5349)
                    .protocol(software.amazon.awscdk.services.ecs.Protocol.UDP)
                    .build()
            ))
            .build();

        // Add container to task definition
        taskDefinition.addContainer("RelayContainer", containerProps);

        // Create an application load balancer
        this.relayLoadBalancer = new ApplicationLoadBalancer(this, "RelayLoadBalancer",
            ApplicationLoadBalancerProps.builder()
                .vpc(vpc)
                .internetFacing(true)
                .securityGroup(relaySecurityGroup)
                .build());

        // Create a target group for the relay servers
        this.relayTargetGroup = new ApplicationTargetGroup(this, "RelayTargetGroup",
            ApplicationTargetGroupProps.builder()
                .vpc(vpc)
                .port(80)
                .protocol(ApplicationProtocol.HTTP)
                .targetType(TargetType.IP)
                .healthCheck(HealthCheck.builder()
                    .path("/health")
                    .port("80")
                    .healthyHttpCodes("200")
                    .healthyThresholdCount(2)
                    .unhealthyThresholdCount(3)
                    .timeout(Duration.seconds(5))
                    .interval(Duration.seconds(30))
                    .build())
                .build());

        // Create a listener for the load balancer
        relayLoadBalancer.addListener("RelayListener",
            software.amazon.awscdk.services.elasticloadbalancingv2.ApplicationListenerProps.builder()
                .loadBalancer(relayLoadBalancer)
                .port(80)
                .defaultTargetGroups(Arrays.asList(relayTargetGroup))
                .build());

        // Create a service for the relay servers
        this.relayService = new FargateService(this, "RelayService",
            FargateServiceProps.builder()
                .cluster(cluster)
                .taskDefinition(taskDefinition)
                .desiredCount(1)
                .securityGroups(Arrays.asList(relaySecurityGroup))
                .assignPublicIp(true)
                .build());

        // Add the service to the target group
        relayTargetGroup.addTarget(relayService);
    }
} 
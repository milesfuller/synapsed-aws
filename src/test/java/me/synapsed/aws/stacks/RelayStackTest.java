package me.synapsed.aws.stacks;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

public class RelayStackTest {
    @Test
    public void testRelayStack() {
        // Create the app and stacks
        App app = new App();
        SecurityStack securityStack = new SecurityStack(app, "SecurityStack", StackProps.builder().build());
        LoggingStack loggingStack = new LoggingStack(app, "LoggingStack");
        RelayStack stack = new RelayStack(app, "RelayStack", StackProps.builder().build(), securityStack, loggingStack);

        // Get the CloudFormation template
        Template template = Template.fromStack(stack);

        // Verify VPC
        template.hasResourceProperties("AWS::EC2::VPC", 
            Match.objectLike(Map.of(
                "EnableDnsHostnames", true,
                "EnableDnsSupport", true
            )));

        // Verify Security Group
        template.hasResourceProperties("AWS::EC2::SecurityGroup", 
            Match.objectLike(Map.of(
                "GroupDescription", "Security group for relay servers",
                "SecurityGroupEgress", Match.arrayWith(Arrays.asList(
                    Match.objectLike(Map.of(
                        "CidrIp", "0.0.0.0/0",
                        "Description", "Allow all outbound traffic by default",
                        "IpProtocol", "-1"
                    ))
                )),
                "SecurityGroupIngress", Match.arrayWith(Arrays.asList(
                    Match.objectLike(Map.of(
                        "CidrIp", "0.0.0.0/0",
                        "FromPort", 80,
                        "IpProtocol", "tcp",
                        "ToPort", 80
                    )),
                    Match.objectLike(Map.of(
                        "CidrIp", "0.0.0.0/0",
                        "FromPort", 443,
                        "IpProtocol", "tcp",
                        "ToPort", 443
                    )),
                    Match.objectLike(Map.of(
                        "CidrIp", "0.0.0.0/0",
                        "FromPort", 3478,
                        "IpProtocol", "udp",
                        "ToPort", 3478
                    )),
                    Match.objectLike(Map.of(
                        "CidrIp", "0.0.0.0/0",
                        "FromPort", 5349,
                        "IpProtocol", "udp",
                        "ToPort", 5349
                    ))
                ))
            )));

        // Verify Log Group
        template.hasResourceProperties("AWS::Logs::LogGroup", 
            Match.objectLike(Map.of(
                "LogGroupName", "/synapsed/relay",
                "RetentionInDays", 30
            )));

        // Verify IAM Role
        template.hasResourceProperties("AWS::IAM::Role", 
            Match.objectLike(Map.of(
                "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                    "Statement", Match.arrayWith(Arrays.asList(
                        Match.objectLike(Map.of(
                            "Action", "sts:AssumeRole",
                            "Effect", "Allow",
                            "Principal", Match.objectLike(Map.of(
                                "Service", "ecs-tasks.amazonaws.com"
                            ))
                        ))
                    ))
                )),
                "Description", "Role for relay servers",
                "ManagedPolicyArns", Match.absent()
            )));

        // Verify ECS Cluster
        template.hasResourceProperties("AWS::ECS::Cluster", Match.anyValue());

        // Verify Task Definition
        template.hasResourceProperties("AWS::ECS::TaskDefinition", 
            Match.objectLike(Map.of(
                "ContainerDefinitions", Match.arrayWith(Arrays.asList(
                    Match.objectLike(Map.of(
                        "Essential", true,
                        "Image", "synapsed/relay-server:latest",
                        "LogConfiguration", Match.objectLike(Map.of(
                            "LogDriver", "awslogs",
                            "Options", Match.objectLike(Map.of(
                                "awslogs-stream-prefix", "relay"
                            ))
                        )),
                        "Memory", 1024,
                        "Name", "RelayContainer",
                        "PortMappings", Match.arrayWith(Arrays.asList(
                            Match.objectLike(Map.of(
                                "ContainerPort", 80,
                                "HostPort", 80,
                                "Protocol", "tcp"
                            )),
                            Match.objectLike(Map.of(
                                "ContainerPort", 443,
                                "HostPort", 443,
                                "Protocol", "tcp"
                            )),
                            Match.objectLike(Map.of(
                                "ContainerPort", 3478,
                                "HostPort", 3478,
                                "Protocol", "udp"
                            )),
                            Match.objectLike(Map.of(
                                "ContainerPort", 5349,
                                "HostPort", 5349,
                                "Protocol", "udp"
                            ))
                        ))
                    ))
                )),
                "Cpu", "512",
                "Memory", "1024",
                "NetworkMode", "awsvpc",
                "RequiresCompatibilities", Match.arrayWith(Arrays.asList("FARGATE"))
            )));

        // Verify Load Balancer
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::LoadBalancer", 
            Match.objectLike(Map.of(
                "Scheme", "internet-facing",
                "Type", "application",
                "SecurityGroups", Match.arrayWith(Arrays.asList(Match.objectLike(Map.of())))
            )));

        // Verify Target Group
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::TargetGroup", 
            Match.objectLike(Map.ofEntries(
                Map.entry("Port", Integer.valueOf(80)),
                Map.entry("Protocol", "HTTP"),
                Map.entry("TargetType", "ip"),
                Map.entry("HealthCheckPath", "/health"),
                Map.entry("HealthCheckPort", "80"),
                Map.entry("HealthyThresholdCount", Integer.valueOf(2)),
                Map.entry("UnhealthyThresholdCount", Integer.valueOf(3)),
                Map.entry("HealthCheckTimeoutSeconds", Integer.valueOf(5)),
                Map.entry("HealthCheckIntervalSeconds", Integer.valueOf(30)),
                Map.entry("VpcId", Match.anyValue())
            )));

        // Verify Listener
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::Listener", 
            Match.objectLike(Map.of(
                "Port", 80,
                "Protocol", "HTTP",
                "LoadBalancerArn", Match.anyValue(),
                "DefaultActions", Match.arrayWith(Arrays.asList(
                    Match.objectLike(Map.of(
                        "Type", "forward",
                        "TargetGroupArn", Match.anyValue()
                    ))
                ))
            )));

        // Verify Fargate Service
        template.hasResourceProperties("AWS::ECS::Service", 
            Match.objectLike(Map.of(
                "LaunchType", "FARGATE",
                "DesiredCount", 1,
                "NetworkConfiguration", Match.objectLike(Map.of(
                    "AwsvpcConfiguration", Match.objectLike(Map.of(
                        "AssignPublicIp", "ENABLED",
                        "SecurityGroups", Match.arrayWith(Arrays.asList(Match.objectLike(Map.of()))),
                        "Subnets", Match.arrayWith(Arrays.asList(Match.objectLike(Map.of())))
                    ))
                ))
            )));

        // Verify stack outputs
        assertNotNull(stack.getRelayService());
        assertNotNull(stack.getRelayLoadBalancer());
        assertNotNull(stack.getRelayTargetGroup());
        assertNotNull(stack.getRelayRole());
        assertNotNull(stack.getRelayLogGroup());
    }
}
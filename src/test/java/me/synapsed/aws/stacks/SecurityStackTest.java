package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class SecurityStackTest {

    @Test
    void testSecurityStack() {
        // Create the app and stack
        App app = new App();
        SecurityStack stack = new SecurityStack(app, "TestSecurityStack", null);
        Template template = Template.fromStack(stack);

        // Verify AWS Organization
        template.hasResourceProperties("AWS::Organizations::Organization", 
            Match.objectLike(Map.of("FeatureSet", "ALL")));

        // Verify Organizational Units
        template.hasResourceProperties("AWS::Organizations::OrganizationalUnit", 
            Match.objectLike(Map.of("Name", "Security")));

        template.hasResourceProperties("AWS::Organizations::OrganizationalUnit", 
            Match.objectLike(Map.of("Name", "Infrastructure")));

        template.hasResourceProperties("AWS::Organizations::OrganizationalUnit", 
            Match.objectLike(Map.of("Name", "Workloads")));

        // Verify Security Audit Role
        template.hasResourceProperties("AWS::IAM::Role", 
            Match.objectLike(Map.of(
                "Description", "Role for security auditing across accounts",
                "MaxSessionDuration", 3600
            )));

        // Verify Logging Read Role
        template.hasResourceProperties("AWS::IAM::Role", 
            Match.objectLike(Map.of(
                "Description", "Role for reading logs across accounts",
                "MaxSessionDuration", 3600
            )));

        // Verify Logging Read Role policies
        template.hasResourceProperties("AWS::IAM::Policy", 
            Match.objectLike(Map.of(
                "PolicyDocument", Match.objectLike(Map.of(
                    "Statement", Match.arrayWith(Arrays.asList(
                        Match.objectLike(Map.of(
                            "Effect", "Allow",
                            "Action", Match.arrayWith(Arrays.asList(
                                "s3:GetObject",
                                "s3:ListBucket",
                                "logs:GetLogEvents",
                                "logs:FilterLogEvents"
                            )),
                            "Resource", "*"
                        ))
                    ))
                ))
            )));

        // Verify Security Audit Role CloudWatch Logs permissions
        template.hasResourceProperties("AWS::IAM::Policy", 
            Match.objectLike(Map.of(
                "PolicyDocument", Match.objectLike(Map.of(
                    "Statement", Match.arrayWith(Arrays.asList(
                        Match.objectLike(Map.of(
                            "Effect", "Allow",
                            "Action", Match.arrayWith(Arrays.asList(
                                "logs:DescribeLogGroups",
                                "logs:DescribeLogStreams",
                                "logs:GetLogEvents",
                                "logs:FilterLogEvents",
                                "logs:GetLogGroupFields",
                                "logs:GetLogRecord",
                                "logs:GetQueryResults",
                                "logs:StartQuery",
                                "logs:StopQuery"
                            )),
                            "Resource", "arn:aws:logs:*:*:log-group:*"
                        ))
                    ))
                ))
            )));
    }
} 
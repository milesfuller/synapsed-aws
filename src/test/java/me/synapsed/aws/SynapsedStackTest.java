package me.synapsed.aws;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

public class SynapsedStackTest {
    @Test
    public void testStackCreation() {
        // Create a new app
        App app = new App();
        
        // Create the stack
        SynapsedStack stack = new SynapsedStack(app, "TestStack", null);
        
        // Prepare the template
        Template template = Template.fromStack(stack);
        
        // Assert that the IAM role exists
        template.hasResourceProperties("AWS::IAM::Role", Map.of(
            "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                "Statement", Match.arrayWith(List.of(Map.of(
                    "Action", "sts:AssumeRole",
                    "Effect", "Allow",
                    "Principal", Map.of(
                        "Service", "lambda.amazonaws.com"
                    )
                )))
            ))
        ));
        
        // Assert that the role has the required permissions
        template.hasResourceProperties("AWS::IAM::Policy", Map.of(
            "PolicyDocument", Match.objectLike(Map.of(
                "Statement", Match.arrayWith(List.of(Map.of(
                    "Action", Match.arrayWith(List.of(
                        "logs:CreateLogGroup",
                        "logs:CreateLogStream",
                        "logs:PutLogEvents"
                    )),
                    "Effect", "Allow",
                    "Resource", "arn:aws:logs:*:*:*"
                )))
            ))
        ));
    }
} 
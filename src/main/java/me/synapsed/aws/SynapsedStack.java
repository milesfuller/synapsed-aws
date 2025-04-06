package me.synapsed.aws;

import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.constructs.Construct;

public class SynapsedStack extends Stack {
    public SynapsedStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public SynapsedStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Create an IAM role for the stack
        Role role = Role.Builder.create(this, "SynapsedRole")
            .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
            .build();

        // Add basic permissions
        role.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(java.util.Arrays.asList(
                "logs:CreateLogGroup",
                "logs:CreateLogStream",
                "logs:PutLogEvents"
            ))
            .resources(java.util.Arrays.asList("arn:aws:logs:*:*:*"))
            .build());

        // The code that defines your stack goes here
        // We'll add the individual stacks as we implement them
    }
} 
package me.synapsed.aws;

import me.synapsed.aws.stacks.AlertingStack;
import me.synapsed.aws.stacks.AuthenticationStack;
import me.synapsed.aws.stacks.ConfigurationManagementStack;
import me.synapsed.aws.stacks.LoggingStack;
import me.synapsed.aws.stacks.RelayStack;
import me.synapsed.aws.stacks.SecurityMonitoringStack;
import me.synapsed.aws.stacks.SecurityStack;
import me.synapsed.aws.stacks.SubscriptionStack;
import me.synapsed.aws.stacks.WebAppStack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class SynapsedApp {
    public static void main(final String[] args) {
        App app = new App();

        // Example: Read environment name from CDK context (e.g., cdk.json or CLI --context)
        String environmentName = (String) app.getNode().tryGetContext("envName");
        if (environmentName == null) {
            environmentName = "dev"; // Default to dev if not set
        }

        // Example: Use SSM Parameter Store for secrets or sensitive config (not shown here)
        // String dbPassword = ssm.StringParameter.valueForStringParameter(this, "/myapp/dbPassword");

        // Pass environment name as a tag or environment variable to stacks
        StackProps baseProps = StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build();

        // Create the configuration management stack
        new ConfigurationManagementStack(app, "ConfigurationManagementStack", baseProps);

        // Create the security stack
        SecurityStack securityStack = new SecurityStack(app, "SecurityStack", baseProps);

        // Create the logging stack
        LoggingStack loggingStack = new LoggingStack(app, "LoggingStack");

        // Create the security monitoring stack
        SecurityMonitoringStack securityMonitoringStack = new SecurityMonitoringStack(app, "SecurityMonitoringStack", baseProps, loggingStack);

        // Create the alerting stack
        new AlertingStack(app, "AlertingStack", baseProps, loggingStack, securityMonitoringStack, null, null);

        // Create the web application stack
        new WebAppStack(app, "WebAppStack", baseProps, securityStack, loggingStack);

        // Create the authentication stack
        new AuthenticationStack(app, "AuthenticationStack", baseProps, securityStack, loggingStack);

        // Create the subscription stack
        new SubscriptionStack(app, "SubscriptionStack", baseProps, securityStack, loggingStack);

        // Create the relay stack
        new RelayStack(app, "RelayStack", baseProps, securityStack, loggingStack);

        app.synth();
    }
} 
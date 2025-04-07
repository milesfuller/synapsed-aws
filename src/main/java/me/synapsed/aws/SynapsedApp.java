package me.synapsed.aws;

import me.synapsed.aws.stacks.AlertingStack;
import me.synapsed.aws.stacks.AuthenticationStack;
import me.synapsed.aws.stacks.ConfigurationManagementStack;
import me.synapsed.aws.stacks.LoggingStack;
import me.synapsed.aws.stacks.RelayStack;
import me.synapsed.aws.stacks.SecurityMonitoringStack;
import me.synapsed.aws.stacks.SecurityStack;
import me.synapsed.aws.stacks.WebAppStack;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class SynapsedApp {
    public static void main(final String[] args) {
        App app = new App();

        // Create the configuration management stack
        new ConfigurationManagementStack(app, "ConfigurationManagementStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build());

        // Create the security stack
        SecurityStack securityStack = new SecurityStack(app, "SecurityStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build());

        // Create the logging stack
        LoggingStack loggingStack = new LoggingStack(app, "LoggingStack");

        // Create the security monitoring stack
        SecurityMonitoringStack securityMonitoringStack = new SecurityMonitoringStack(app, "SecurityMonitoringStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build(), loggingStack);

        // Create the alerting stack
        new AlertingStack(app, "AlertingStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build(), loggingStack, securityMonitoringStack, null, null);

        // Create the web application stack
        new WebAppStack(app, "WebAppStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build(), securityStack, loggingStack);

        // Create the authentication stack
        new AuthenticationStack(app, "AuthenticationStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build(), securityStack, loggingStack);

        // Create the relay stack
        new RelayStack(app, "RelayStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build(), securityStack, loggingStack);

        app.synth();
    }
} 
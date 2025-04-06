package me.synapsed.aws;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

import me.synapsed.aws.stacks.ConfigurationManagementStack;

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

        // Create the main stack
        new SynapsedStack(app, "SynapsedStack", StackProps.builder()
            .env(Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv("CDK_DEFAULT_REGION"))
                .build())
            .build());

        app.synth();
    }
} 
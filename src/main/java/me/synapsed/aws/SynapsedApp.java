package me.synapsed.aws;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public class SynapsedApp {
    public static void main(final String[] args) {
        App app = new App();

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
package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class ConfigurationManagementStackTest {

    @Test
    void testConfigurationManagementStack() {
        // Create the app and stack
        App app = new App();
        ConfigurationManagementStack stack = new ConfigurationManagementStack(app, "TestConfigStack", null);
        Template template = Template.fromStack(stack);

        // Verify KMS key
        template.hasResourceProperties("AWS::KMS::Key", 
            Match.objectLike(Map.of(
                "Description", "KMS key for configuration encryption",
                "EnableKeyRotation", true,
                "KeySpec", "SYMMETRIC_DEFAULT",
                "KeyUsage", "ENCRYPT_DECRYPT"
            )));

        // Verify Parameter Store parameter
        template.hasResourceProperties("AWS::SSM::Parameter", 
            Match.objectLike(Map.of(
                "Name", "/synapsed/app/config",
                "Type", "String",
                "Description", "Application configuration parameters",
                "Tier", "Standard",
                "Value", "{\"featureFlags\":{\"newUI\":false,\"betaFeatures\":false},\"settings\":{\"timeout\":30,\"retryCount\":3}}"
            )));

        // Verify Secrets Manager secret
        template.hasResourceProperties("AWS::SecretsManager::Secret", 
            Match.objectLike(Map.of(
                "Name", "synapsed/app/secrets",
                "Description", "Application secrets"
            )));

        // Verify AppConfig application
        template.hasResourceProperties("AWS::AppConfig::Application", 
            Match.objectLike(Map.of(
                "Name", "synapsed-app",
                "Description", "Synapsed application configuration"
            )));

        // Verify AppConfig environment
        template.hasResourceProperties("AWS::AppConfig::Environment", 
            Match.objectLike(Map.of(
                "Name", "production",
                "Description", "Production environment"
            )));

        // Verify AppConfig configuration profile
        template.hasResourceProperties("AWS::AppConfig::ConfigurationProfile", 
            Match.objectLike(Map.of(
                "Name", "synapsed-config",
                "Description", "Synapsed configuration profile",
                "LocationUri", "hosted"
            )));

        // Verify AppConfig deployment strategy
        template.hasResourceProperties("AWS::AppConfig::DeploymentStrategy", 
            Match.objectLike(Map.of(
                "Name", "synapsed-deployment",
                "Description", "Synapsed deployment strategy",
                "DeploymentDurationInMinutes", 15,
                "GrowthFactor", 20.0,
                "ReplicateTo", "NONE"
            )));

        // Verify AppConfig role permissions
        template.hasResourceProperties("AWS::IAM::Policy", 
            Match.objectLike(Map.of(
                "PolicyDocument", Match.objectLike(Map.of(
                    "Statement", Match.arrayWith(Arrays.asList(
                        Match.objectLike(Map.of(
                            "Effect", "Allow",
                            "Action", Arrays.asList(
                                "ssm:GetParameter",
                                "ssm:GetParameters",
                                "ssm:GetParametersByPath"
                            ),
                            "Resource", "arn:aws:ssm:*:*:parameter/synapsed/*"
                        )),
                        Match.objectLike(Map.of(
                            "Effect", "Allow",
                            "Action", "secretsmanager:GetSecretValue",
                            "Resource", "arn:aws:secretsmanager:*:*:secret:synapsed/*"
                        )),
                        Match.objectLike(Map.of(
                            "Effect", "Allow",
                            "Action", Arrays.asList(
                                "logs:CreateLogGroup",
                                "logs:CreateLogStream",
                                "logs:PutLogEvents",
                                "logs:DescribeLogStreams"
                            ),
                            "Resource", "arn:aws:logs:*:*:log-group:/aws/appconfig/*"
                        ))
                    ))
                ))
            )));
    }
} 
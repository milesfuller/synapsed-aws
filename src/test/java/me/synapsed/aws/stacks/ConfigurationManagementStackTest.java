package me.synapsed.aws.stacks;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

public class ConfigurationManagementStackTest {

    @Test
    public void testStackCreation() {
        App app = new App();
        ConfigurationManagementStack stack = new ConfigurationManagementStack(app, "TestStack", null);
        Template template = Template.fromStack(stack);

        // Verify KMS Key
        template.hasResourceProperties("AWS::KMS::Key", Map.of(
            "Description", "KMS key for configuration encryption",
            "EnableKeyRotation", true,
            "KeySpec", "SYMMETRIC_DEFAULT",
            "KeyUsage", "ENCRYPT_DECRYPT"
        ));

        // Verify Parameter Store Parameter
        template.hasResourceProperties("AWS::SSM::Parameter", Map.of(
            "Name", "/synapsed/app/config",
            "Type", "String",
            "Tier", "Standard",
            "Description", "Application configuration parameters"
        ));

        // Verify Secrets Manager Secret
        template.hasResourceProperties("AWS::SecretsManager::Secret", Map.of(
            "Name", "synapsed/app/secrets",
            "Description", "Application secrets",
            "GenerateSecretString", Match.objectLike(Map.of(
                "SecretStringTemplate", "{\"username\":\"admin\"}",
                "GenerateStringKey", "password",
                "ExcludeCharacters", "\"@/\\",
                "PasswordLength", 16
            ))
        ));

        // Verify AppConfig Application
        template.hasResourceProperties("AWS::AppConfig::Application", Map.of(
            "Name", "synapsed-app",
            "Description", "Synapsed application configuration"
        ));

        // Verify AppConfig Environment
        template.hasResourceProperties("AWS::AppConfig::Environment", Map.of(
            "Name", "production",
            "Description", "Production environment"
        ));

        // Verify AppConfig Configuration Profile
        template.hasResourceProperties("AWS::AppConfig::ConfigurationProfile", Map.of(
            "Name", "synapsed-config",
            "Description", "Synapsed configuration profile",
            "LocationUri", "hosted"
        ));

        // Verify AppConfig Deployment Strategy
        template.hasResourceProperties("AWS::AppConfig::DeploymentStrategy", Map.of(
            "Name", "synapsed-deployment",
            "Description", "Synapsed deployment strategy",
            "DeploymentDurationInMinutes", 15,
            "GrowthFactor", 20.0,
            "ReplicateTo", "NONE"
        ));

        // Verify IAM Role for AppConfig
        template.hasResourceProperties("AWS::IAM::Role", Match.objectLike(Map.of(
            "AssumeRolePolicyDocument", Match.objectLike(Map.of(
                "Statement", List.of(Map.of(
                    "Action", "sts:AssumeRole",
                    "Effect", "Allow",
                    "Principal", Map.of(
                        "Service", "appconfig.amazonaws.com"
                    )
                ))
            )),
            "Description", "Role for AppConfig to access configuration sources"
        )));

        // Verify base stack IAM policy
        template.hasResourceProperties("AWS::IAM::Policy", Match.objectLike(Map.of(
            "PolicyDocument", Match.objectLike(Map.of(
                "Statement", List.of(Map.of(
                    "Effect", "Allow",
                    "Action", List.of(
                        "logs:CreateLogGroup",
                        "logs:CreateLogStream",
                        "logs:PutLogEvents"
                    ),
                    "Resource", "arn:aws:logs:*:*:*"
                ))
            ))
        )));

        // Verify AppConfig Role Policy
        template.hasResourceProperties("AWS::IAM::Policy", Match.objectLike(Map.of(
            "PolicyDocument", Match.objectLike(Map.of(
                "Statement", List.of(
                    Map.of(
                        "Effect", "Allow",
                        "Action", List.of(
                            "ssm:GetParameter",
                            "ssm:GetParameters",
                            "ssm:GetParametersByPath"
                        ),
                        "Resource", "arn:aws:ssm:*:*:parameter/synapsed/*"
                    ),
                    Map.of(
                        "Effect", "Allow",
                        "Action", "secretsmanager:GetSecretValue",
                        "Resource", "arn:aws:secretsmanager:*:*:secret:synapsed/*"
                    )
                )
            ))
        )));
    }
} 
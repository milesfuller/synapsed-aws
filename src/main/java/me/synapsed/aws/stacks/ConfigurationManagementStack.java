package me.synapsed.aws.stacks;

import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ssm.StringParameter;
import software.amazon.awscdk.services.ssm.StringParameterProps;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.amazon.awscdk.services.secretsmanager.SecretProps;
import software.amazon.awscdk.services.appconfig.CfnApplication;
import software.amazon.awscdk.services.appconfig.CfnEnvironment;
import software.amazon.awscdk.services.appconfig.CfnConfigurationProfile;
import software.amazon.awscdk.services.appconfig.CfnDeploymentStrategy;
import software.amazon.awscdk.services.kms.Key;
import software.amazon.awscdk.services.kms.KeyProps;
import software.amazon.awscdk.services.kms.KeySpec;
import software.amazon.awscdk.services.kms.KeyUsage;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import software.constructs.Construct;

import me.synapsed.aws.SynapsedStack;
import me.synapsed.aws.utils.NamingUtils;

import java.util.Arrays;

import software.amazon.awscdk.services.appconfig.CfnApplicationProps;
import software.amazon.awscdk.services.appconfig.CfnEnvironmentProps;
import software.amazon.awscdk.services.appconfig.CfnConfigurationProfileProps;
import software.amazon.awscdk.services.appconfig.CfnDeploymentStrategyProps;

import lombok.Getter;

/**
 * Stack for managing configuration resources including KMS keys, Parameter Store parameters,
 * Secrets Manager secrets, and AppConfig resources.
 */
@Getter
public class ConfigurationManagementStack extends SynapsedStack {

    private final Key configKey;
    private final StringParameter appConfigParam;
    private final Secret appSecret;
    private final CfnApplication appConfigApp;
    private final CfnEnvironment appConfigEnv;
    private final CfnConfigurationProfile appConfigProfile;
    private final CfnDeploymentStrategy deploymentStrategy;

    public ConfigurationManagementStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Create KMS key for configuration encryption
        this.configKey = new Key(this, NamingUtils.resourceName(this, "ConfigKey"), KeyProps.builder()
            .description("KMS key for configuration encryption")
            .keySpec(KeySpec.SYMMETRIC_DEFAULT)
            .keyUsage(KeyUsage.ENCRYPT_DECRYPT)
            .enableKeyRotation(true)
            .build());

        // Create Parameter Store parameter for application configuration
        this.appConfigParam = new StringParameter(this, NamingUtils.resourceName(this, "AppConfigParam"), StringParameterProps.builder()
            .parameterName("/synapsed/app/config")
            .stringValue("{\"featureFlags\":{\"newUI\":false,\"betaFeatures\":false},\"settings\":{\"timeout\":30,\"retryCount\":3}}")
            .description("Application configuration parameters")
            .tier(software.amazon.awscdk.services.ssm.ParameterTier.STANDARD)
            .build());

        // Create Secrets Manager secret for sensitive information
        this.appSecret = new Secret(this, NamingUtils.resourceName(this, "AppSecret"), SecretProps.builder()
            .secretName("synapsed/app/secrets")
            .description("Application secrets")
            .encryptionKey(configKey)
            .generateSecretString(software.amazon.awscdk.services.secretsmanager.SecretStringGenerator.builder()
                .secretStringTemplate("{\"username\":\"admin\"}")
                .generateStringKey("password")
                .excludeCharacters("\"@/\\")
                .passwordLength(16)
                .build())
            .build());

        // Create AppConfig application
        CfnApplicationProps appProps = CfnApplicationProps.builder()
            .name("synapsed-app")
            .description("Synapsed application configuration")
            .build();
        this.appConfigApp = new CfnApplication(this, NamingUtils.resourceName(this, "AppConfigApp"), appProps);

        // Create AppConfig environment
        CfnEnvironmentProps envProps = CfnEnvironmentProps.builder()
            .applicationId(this.appConfigApp.getRef())
            .name("production")
            .description("Production environment")
            .build();
        this.appConfigEnv = new CfnEnvironment(this, NamingUtils.resourceName(this, "AppConfigEnv"), envProps);

        // Create AppConfig configuration profile
        CfnConfigurationProfileProps profileProps = CfnConfigurationProfileProps.builder()
            .applicationId(this.appConfigApp.getRef())
            .name("synapsed-config")
            .description("Synapsed configuration profile")
            .locationUri("hosted")
            .retrievalRoleArn(createAppConfigRole().getRoleArn())
            .build();
        this.appConfigProfile = new CfnConfigurationProfile(this, NamingUtils.resourceName(this, "AppConfigProfile"), profileProps);

        // Create deployment strategy
        CfnDeploymentStrategyProps strategyProps = CfnDeploymentStrategyProps.builder()
            .name("synapsed-deployment")
            .description("Synapsed deployment strategy")
            .deploymentDurationInMinutes(15)
            .growthFactor(20.0)
            .replicateTo("NONE")
            .build();
        this.deploymentStrategy = new CfnDeploymentStrategy(this, NamingUtils.resourceName(this, "AppConfigDeploymentStrategy"), strategyProps);
    }

    private Role createAppConfigRole() {
        Role role = Role.Builder.create(this, NamingUtils.resourceName(this, "AppConfigRole"))
            .assumedBy(new ServicePrincipal("appconfig.amazonaws.com"))
            .description("Role for AppConfig to access configuration sources")
            .build();

        // Add permissions to access Parameter Store
        role.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "ssm:GetParameter",
                "ssm:GetParameters",
                "ssm:GetParametersByPath"
            ))
            .resources(Arrays.asList(
                "arn:aws:ssm:*:*:parameter/synapsed/*"
            ))
            .build());

        // Add permissions to access Secrets Manager
        role.addToPolicy(PolicyStatement.Builder.create()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "secretsmanager:GetSecretValue"
            ))
            .resources(Arrays.asList(
                "arn:aws:secretsmanager:*:*:secret:synapsed/*"
            ))
            .build());

        return role;
    }
} 
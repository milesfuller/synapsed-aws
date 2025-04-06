package me.synapsed.aws.stacks;

import java.util.Arrays;

import lombok.Getter;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.CfnOutputProps;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.PolicyStatementProps;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.RoleProps;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.organizations.CfnOrganization;
import software.amazon.awscdk.services.organizations.CfnOrganizationProps;
import software.amazon.awscdk.services.organizations.CfnOrganizationalUnit;
import software.amazon.awscdk.services.organizations.CfnOrganizationalUnitProps;
import software.constructs.Construct;

@Getter
public class SecurityStack extends Stack {
    private final CfnOrganization organization;
    private final CfnOrganizationalUnit securityOU;
    private final CfnOrganizationalUnit infrastructureOU;
    private final CfnOrganizationalUnit workloadsOU;
    private final Role securityAuditRole;
    private final Role loggingReadRole;

    public SecurityStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Create AWS Organization
        organization = new CfnOrganization(this, "SynapsedOrg", CfnOrganizationProps.builder()
            .featureSet("ALL")
            .build());

        // Create Organizational Units
        securityOU = new CfnOrganizationalUnit(this, "SecurityOU", CfnOrganizationalUnitProps.builder()
            .name("Security")
            .parentId(organization.getAttrRootId())
            .build());

        infrastructureOU = new CfnOrganizationalUnit(this, "InfrastructureOU", CfnOrganizationalUnitProps.builder()
            .name("Infrastructure")
            .parentId(organization.getAttrRootId())
            .build());

        workloadsOU = new CfnOrganizationalUnit(this, "WorkloadsOU", CfnOrganizationalUnitProps.builder()
            .name("Workloads")
            .parentId(organization.getAttrRootId())
            .build());

        // Create Security Audit Role
        securityAuditRole = new Role(this, "SecurityAuditRole", RoleProps.builder()
            .assumedBy(new ServicePrincipal("iam.amazonaws.com"))
            .description("Role for security auditing across accounts")
            .maxSessionDuration(Duration.hours(1))
            .build());

        // Attach required policies to Security Audit Role
        securityAuditRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("SecurityAudit")
        );
        securityAuditRole.addManagedPolicy(
            ManagedPolicy.fromAwsManagedPolicyName("AWSConfigUserAccess")
        );

        // Add CloudWatch Logs permissions to Security Audit Role
        securityAuditRole.addToPolicy(new PolicyStatement(PolicyStatementProps.builder()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "logs:DescribeLogGroups",
                "logs:DescribeLogStreams",
                "logs:GetLogEvents",
                "logs:FilterLogEvents",
                "logs:GetLogGroupFields",
                "logs:GetLogRecord",
                "logs:GetQueryResults",
                "logs:StartQuery",
                "logs:StopQuery"
            ))
            .resources(Arrays.asList(
                "arn:aws:logs:*:*:log-group:*"
            ))
            .build()));

        // Create Logging Read Role
        loggingReadRole = new Role(this, "LoggingReadRole", RoleProps.builder()
            .assumedBy(new ServicePrincipal("iam.amazonaws.com"))
            .description("Role for reading logs across accounts")
            .maxSessionDuration(Duration.hours(1))
            .build());

        // Attach S3 and CloudWatch Logs read permissions
        loggingReadRole.addToPolicy(new PolicyStatement(PolicyStatementProps.builder()
            .effect(Effect.ALLOW)
            .actions(Arrays.asList(
                "s3:GetObject",
                "s3:ListBucket",
                "logs:GetLogEvents",
                "logs:FilterLogEvents"
            ))
            .resources(Arrays.asList("*"))
            .build()));

        // Output important values
        new CfnOutput(this, "OrganizationId", CfnOutputProps.builder()
            .value(organization.getAttrId())
            .description("AWS Organization ID")
            .build());

        new CfnOutput(this, "SecurityOUId", CfnOutputProps.builder()
            .value(securityOU.getAttrId())
            .description("Security OU ID")
            .build());

        new CfnOutput(this, "SecurityAuditRoleArn", CfnOutputProps.builder()
            .value(securityAuditRole.getRoleArn())
            .description("Security Audit Role ARN")
            .build());
    }
} 
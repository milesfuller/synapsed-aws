package me.synapsed.aws.stacks;

import java.util.Arrays;

import lombok.Getter;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.Tags;
import software.amazon.awscdk.services.certificatemanager.Certificate;
import software.amazon.awscdk.services.certificatemanager.CertificateProps;
import software.amazon.awscdk.services.certificatemanager.CertificateValidation;
import software.amazon.awscdk.services.cloudfront.CloudFrontWebDistribution;
import software.amazon.awscdk.services.cloudfront.CloudFrontWebDistributionProps;
import software.amazon.awscdk.services.cloudfront.S3OriginConfig;
import software.amazon.awscdk.services.cloudfront.SSLMethod;
import software.amazon.awscdk.services.cloudfront.SecurityPolicyProtocol;
import software.amazon.awscdk.services.cloudfront.ViewerCertificate;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.route53.HostedZone;
import software.amazon.awscdk.services.route53.HostedZoneProviderProps;
import software.amazon.awscdk.services.route53.IHostedZone;
import software.amazon.awscdk.services.route53.RecordSet;
import software.amazon.awscdk.services.route53.RecordSetProps;
import software.amazon.awscdk.services.route53.RecordType;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.BucketProps;
import software.constructs.Construct;

/**
 * Web Application Stack for Synapsed platform.
 * Implements infrastructure for hosting the landing page and PWAs.
 */
@Getter
public class WebAppStack extends Stack {
    private final Bucket landingPageBucket;
    private final Bucket notesAppBucket;
    private final Bucket messengerAppBucket;
    private final CloudFrontWebDistribution landingPageDistribution;
    private final CloudFrontWebDistribution notesAppDistribution;
    private final CloudFrontWebDistribution messengerAppDistribution;
    private final IHostedZone hostedZone;
    private final Certificate certificate;

    public WebAppStack(final Construct scope, final String id, final StackProps props,
                      final SecurityStack securityStack, final LoggingStack loggingStack) {
        super(scope, id, props);

        // Add webapp-specific and cost allocation tags
        Tags.of(this).add("WebApp", "Enabled");
        Tags.of(this).add("CostCenter", "P2PPlatform");
        Tags.of(this).add("Owner", "PlatformTeam");
        Tags.of(this).add("Environment", System.getenv().getOrDefault("ENVIRONMENT", "dev"));
        Tags.of(this).add("StaticHosting", "True");
        Tags.of(this).add("CDN", "Enabled");

        // Get the hosted zone for synapsed.me
        this.hostedZone = HostedZone.fromLookup(this, "SynapsedHostedZone",
            HostedZoneProviderProps.builder()
                .domainName("synapsed.me")
                .build());

        // Create a certificate for all subdomains
        this.certificate = new Certificate(this, "SynapsedCertificate",
            CertificateProps.builder()
                .domainName("synapsed.me")
                .validation(CertificateValidation.fromDns(hostedZone))
                .subjectAlternativeNames(Arrays.asList(
                    "*.synapsed.me"
                ))
                .build());

        // Create S3 buckets for static hosting
        this.landingPageBucket = new Bucket(this, "LandingPageBucket",
            BucketProps.builder()
                .bucketName("synapsed-landing")
                .publicReadAccess(false)
                .blockPublicAccess(software.amazon.awscdk.services.s3.BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
                .autoDeleteObjects(false)
                .versioned(true)
                .build());

        this.notesAppBucket = new Bucket(this, "NotesAppBucket",
            BucketProps.builder()
                .bucketName("synapsed-notes")
                .publicReadAccess(false)
                .blockPublicAccess(software.amazon.awscdk.services.s3.BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
                .autoDeleteObjects(false)
                .versioned(true)
                .build());

        this.messengerAppBucket = new Bucket(this, "MessengerAppBucket",
            BucketProps.builder()
                .bucketName("synapsed-messenger")
                .publicReadAccess(false)
                .blockPublicAccess(software.amazon.awscdk.services.s3.BlockPublicAccess.BLOCK_ALL)
                .removalPolicy(software.amazon.awscdk.RemovalPolicy.RETAIN)
                .autoDeleteObjects(false)
                .versioned(true)
                .build());

        // Create CloudFront distributions for each application
        this.landingPageDistribution = new CloudFrontWebDistribution(this, "LandingPageDistribution",
            CloudFrontWebDistributionProps.builder()
                .originConfigs(Arrays.asList(
                    software.amazon.awscdk.services.cloudfront.SourceConfiguration.builder()
                        .s3OriginSource(S3OriginConfig.builder()
                            .s3BucketSource(landingPageBucket)
                            .build())
                        .behaviors(Arrays.asList(
                            software.amazon.awscdk.services.cloudfront.Behavior.builder()
                                .isDefaultBehavior(true)
                                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                                .build()
                        ))
                        .build()
                ))
                .viewerCertificate(ViewerCertificate.fromAcmCertificate(certificate,
                    software.amazon.awscdk.services.cloudfront.ViewerCertificateOptions.builder()
                        .aliases(Arrays.asList("synapsed.me"))
                        .securityPolicy(SecurityPolicyProtocol.TLS_V1_2_2021)
                        .sslMethod(SSLMethod.SNI)
                        .build()))
                .build());

        this.notesAppDistribution = new CloudFrontWebDistribution(this, "NotesAppDistribution",
            CloudFrontWebDistributionProps.builder()
                .originConfigs(Arrays.asList(
                    software.amazon.awscdk.services.cloudfront.SourceConfiguration.builder()
                        .s3OriginSource(S3OriginConfig.builder()
                            .s3BucketSource(notesAppBucket)
                            .build())
                        .behaviors(Arrays.asList(
                            software.amazon.awscdk.services.cloudfront.Behavior.builder()
                                .isDefaultBehavior(true)
                                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                                .build()
                        ))
                        .build()
                ))
                .viewerCertificate(ViewerCertificate.fromAcmCertificate(certificate,
                    software.amazon.awscdk.services.cloudfront.ViewerCertificateOptions.builder()
                        .aliases(Arrays.asList("notes.synapsed.me"))
                        .securityPolicy(SecurityPolicyProtocol.TLS_V1_2_2021)
                        .sslMethod(SSLMethod.SNI)
                        .build()))
                .build());

        this.messengerAppDistribution = new CloudFrontWebDistribution(this, "MessengerAppDistribution",
            CloudFrontWebDistributionProps.builder()
                .originConfigs(Arrays.asList(
                    software.amazon.awscdk.services.cloudfront.SourceConfiguration.builder()
                        .s3OriginSource(S3OriginConfig.builder()
                            .s3BucketSource(messengerAppBucket)
                            .build())
                        .behaviors(Arrays.asList(
                            software.amazon.awscdk.services.cloudfront.Behavior.builder()
                                .isDefaultBehavior(true)
                                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                                .build()
                        ))
                        .build()
                ))
                .viewerCertificate(ViewerCertificate.fromAcmCertificate(certificate,
                    software.amazon.awscdk.services.cloudfront.ViewerCertificateOptions.builder()
                        .aliases(Arrays.asList("messenger.synapsed.me"))
                        .securityPolicy(SecurityPolicyProtocol.TLS_V1_2_2021)
                        .sslMethod(SSLMethod.SNI)
                        .build()))
                .build());

        // Create DNS records for each domain
        new RecordSet(this, "LandingPageDnsRecord",
            RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(software.amazon.awscdk.services.route53.RecordTarget.fromAlias(
                    new software.amazon.awscdk.services.route53.targets.CloudFrontTarget(landingPageDistribution)))
                .zone(hostedZone)
                .build());

        new RecordSet(this, "NotesAppDnsRecord",
            RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(software.amazon.awscdk.services.route53.RecordTarget.fromAlias(
                    new software.amazon.awscdk.services.route53.targets.CloudFrontTarget(notesAppDistribution)))
                .zone(hostedZone)
                .build());

        new RecordSet(this, "MessengerAppDnsRecord",
            RecordSetProps.builder()
                .recordType(RecordType.A)
                .target(software.amazon.awscdk.services.route53.RecordTarget.fromAlias(
                    new software.amazon.awscdk.services.route53.targets.CloudFrontTarget(messengerAppDistribution)))
                .zone(hostedZone)
                .build());

        // Note: In a real implementation, you would add a BucketDeployment to deploy the actual web content
        // For example:
        // new BucketDeployment(this, "DeployLandingPage",
        //     BucketDeploymentProps.builder()
        //         .sources(Arrays.asList(Source.asset("path/to/landing/page")))
        //         .destinationBucket(landingPageBucket)
        //         .build());
    }
} 
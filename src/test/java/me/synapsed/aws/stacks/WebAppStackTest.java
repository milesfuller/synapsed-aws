package me.synapsed.aws.stacks;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Test;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class WebAppStackTest {

    @Test
    void testWebAppStack() {
        // Create the app and stack with environment
        App app = new App();
        StackProps props = StackProps.builder()
            .env(Environment.builder()
                .account("123456789012")
                .region("us-east-1")
                .build())
            .build();
            
        SecurityStack securityStack = new SecurityStack(app, "TestSecurityStack", props);
        LoggingStack loggingStack = new LoggingStack(app, "TestLoggingStack");
        WebAppStack stack = new WebAppStack(app, "TestWebAppStack", props, securityStack, loggingStack);
        Template template = Template.fromStack(stack);

        // Verify S3 buckets
        template.hasResourceProperties("AWS::S3::Bucket", 
            Match.objectLike(Map.of(
                "BucketName", "synapsed-landing",
                "PublicAccessBlockConfiguration", Match.objectLike(Map.of(
                    "BlockPublicAcls", true,
                    "BlockPublicPolicy", true,
                    "IgnorePublicAcls", true,
                    "RestrictPublicBuckets", true
                ))
            )));

        template.hasResourceProperties("AWS::S3::Bucket", 
            Match.objectLike(Map.of(
                "BucketName", "synapsed-notes",
                "PublicAccessBlockConfiguration", Match.objectLike(Map.of(
                    "BlockPublicAcls", true,
                    "BlockPublicPolicy", true,
                    "IgnorePublicAcls", true,
                    "RestrictPublicBuckets", true
                ))
            )));

        template.hasResourceProperties("AWS::S3::Bucket", 
            Match.objectLike(Map.of(
                "BucketName", "synapsed-messenger",
                "PublicAccessBlockConfiguration", Match.objectLike(Map.of(
                    "BlockPublicAcls", true,
                    "BlockPublicPolicy", true,
                    "IgnorePublicAcls", true,
                    "RestrictPublicBuckets", true
                ))
            )));

        // Verify CloudFront distributions
        template.hasResourceProperties("AWS::CloudFront::Distribution", 
            Match.objectLike(Map.of(
                "DistributionConfig", Match.objectLike(Map.of(
                    "Enabled", true,
                    "DefaultRootObject", "index.html",
                    "PriceClass", "PriceClass_100",
                    "HttpVersion", "http2",
                    "IPV6Enabled", true,
                    "Aliases", Match.arrayWith(Arrays.asList("synapsed.me")),
                    "DefaultCacheBehavior", Match.objectLike(Map.of(
                        "ViewerProtocolPolicy", "redirect-to-https"
                    ))
                ))
            )));

        template.hasResourceProperties("AWS::CloudFront::Distribution", 
            Match.objectLike(Map.of(
                "DistributionConfig", Match.objectLike(Map.of(
                    "Enabled", true,
                    "DefaultRootObject", "index.html",
                    "PriceClass", "PriceClass_100",
                    "HttpVersion", "http2",
                    "IPV6Enabled", true,
                    "Aliases", Match.arrayWith(Arrays.asList("notes.synapsed.me")),
                    "DefaultCacheBehavior", Match.objectLike(Map.of(
                        "ViewerProtocolPolicy", "redirect-to-https"
                    ))
                ))
            )));

        template.hasResourceProperties("AWS::CloudFront::Distribution", 
            Match.objectLike(Map.of(
                "DistributionConfig", Match.objectLike(Map.of(
                    "Enabled", true,
                    "DefaultRootObject", "index.html",
                    "PriceClass", "PriceClass_100",
                    "HttpVersion", "http2",
                    "IPV6Enabled", true,
                    "Aliases", Match.arrayWith(Arrays.asList("messenger.synapsed.me")),
                    "DefaultCacheBehavior", Match.objectLike(Map.of(
                        "ViewerProtocolPolicy", "redirect-to-https"
                    ))
                ))
            )));

        // Verify Route53 records
        template.resourceCountIs("AWS::Route53::RecordSet", 3);
        template.hasResourceProperties("AWS::Route53::RecordSet", 
            Match.objectLike(Map.of(
                "Type", "A",
                "Name", "synapsed.me.",
                "AliasTarget", Match.objectLike(Map.of())
            )));
    }
} 
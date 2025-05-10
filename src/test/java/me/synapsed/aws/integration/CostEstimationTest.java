package me.synapsed.aws.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CostEstimationTest {
    @Test
    void testEc2T3MicroOnDemandPrice() {
        // Skip test if no AWS credentials are present
        String accessKey = System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = System.getenv("AWS_SECRET_ACCESS_KEY");
        boolean hasCreds = (accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty());
        Assumptions.assumeTrue(hasCreds, "AWS credentials not found in environment; skipping cost estimation test.");

        // The AWS Pricing API is only available in us-east-1
        PricingClient pricingClient = PricingClient.builder()
                .region(Region.US_EAST_1)
                .build();

        // Query for t3.micro Linux in us-east-1
        GetProductsRequest request = GetProductsRequest.builder()
                .serviceCode("AmazonEC2")
                .filters(
                        software.amazon.awssdk.services.pricing.model.Filter.builder()
                                .type("TERM_MATCH")
                                .field("instanceType")
                                .value("t3.micro")
                                .build(),
                        software.amazon.awssdk.services.pricing.model.Filter.builder()
                                .type("TERM_MATCH")
                                .field("location")
                                .value("US East (N. Virginia)")
                                .build(),
                        software.amazon.awssdk.services.pricing.model.Filter.builder()
                                .type("TERM_MATCH")
                                .field("operatingSystem")
                                .value("Linux")
                                .build(),
                        software.amazon.awssdk.services.pricing.model.Filter.builder()
                                .type("TERM_MATCH")
                                .field("preInstalledSw")
                                .value("NA")
                                .build(),
                        software.amazon.awssdk.services.pricing.model.Filter.builder()
                                .type("TERM_MATCH")
                                .field("tenancy")
                                .value("Shared")
                                .build(),
                        software.amazon.awssdk.services.pricing.model.Filter.builder()
                                .type("TERM_MATCH")
                                .field("capacitystatus")
                                .value("Used")
                                .build()
                )
                .maxResults(1)
                .build();

        GetProductsResponse response = pricingClient.getProducts(request);
        List<String> priceList = response.priceList();
        assertTrue(!priceList.isEmpty(), "No price data found for t3.micro in us-east-1");
        System.out.println("Sample EC2 t3.micro price data: " + priceList.get(0));
        // In a real test, parse the JSON and extract the pricePerUnit value
    }
} 
package me.synapsed.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lambda function for generating compliance reports.
 * Collects data from CloudWatch Logs, CloudTrail, and Config,
 * generates compliance reports, and stores them in S3.
 */
public class ComplianceReportGenerator implements RequestHandler<ScheduledEvent, String> {
    private final CloudWatchLogsClient logsClient;
    private final SnsClient snsClient;
    private final S3Client s3Client;
    private final ObjectMapper objectMapper;
    private final String logGroupName;
    private final String notificationTopicArn;
    private final String complianceBucket;

    public ComplianceReportGenerator() {
        this.logsClient = CloudWatchLogsClient.create();
        this.snsClient = SnsClient.create();
        this.s3Client = S3Client.create();
        this.objectMapper = new ObjectMapper();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
        this.complianceBucket = System.getenv("COMPLIANCE_BUCKET");
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            context.getLogger().log("Starting compliance report generation");
            
            // Generate report
            Map<String, Object> report = generateComplianceReport();
            
            // Store report in S3
            String reportKey = "reports/compliance-" + Instant.now().toString() + ".json";
            storeReportInS3(report, reportKey);
            
            // Send notification
            sendNotification("Compliance report generated: " + reportKey);
            
            return "Successfully generated compliance report";
        } catch (Exception e) {
            context.getLogger().log("Error generating compliance report: " + e.getMessage());
            throw new RuntimeException("Failed to generate compliance report", e);
        }
    }

    private Map<String, Object> generateComplianceReport() throws Exception {
        // TODO: Implement report generation logic
        // This should collect data from CloudWatch Logs, CloudTrail, and Config
        // For now, returning a placeholder report
        return Map.of(
            "timestamp", Instant.now().toString(),
            "reportId", UUID.randomUUID().toString(),
            "status", "COMPLIANT",
            "findings", new ArrayList<>()
        );
    }

    private void storeReportInS3(Map<String, Object> report, String key) throws Exception {
        String reportJson = objectMapper.writeValueAsString(report);
        
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
            .bucket(complianceBucket)
            .key(key)
            .build();
            
        s3Client.putObject(putObjectRequest, RequestBody.fromString(reportJson));
    }

    private void sendNotification(String message) {
        PublishRequest publishRequest = PublishRequest.builder()
            .topicArn(notificationTopicArn)
            .message(message)
            .build();
            
        snsClient.publish(publishRequest);
    }

    private void logEvent(String message) {
        List<InputLogEvent> logEvents = new ArrayList<>();
        logEvents.add(InputLogEvent.builder()
            .timestamp(System.currentTimeMillis())
            .message(message)
            .build());
            
        PutLogEventsRequest putLogEventsRequest = PutLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamName("compliance-reports")
            .logEvents(logEvents)
            .build();
            
        logsClient.putLogEvents(putLogEventsRequest);
    }
} 
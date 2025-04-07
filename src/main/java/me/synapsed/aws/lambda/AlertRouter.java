package me.synapsed.aws.lambda;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Lambda function for routing alerts based on severity.
 * Routes alerts to appropriate SNS topics and stores them in S3.
 */
public class AlertRouter implements RequestHandler<ScheduledEvent, String> {
    private final CloudWatchLogsClient logsClient;
    private final SnsClient snsClient;
    private final S3Client s3Client;
    private final String logGroupName;
    private final String criticalTopicArn;
    private final String warningTopicArn;
    private final String infoTopicArn;
    private final String alertsBucket;

    public AlertRouter() {
        this.logsClient = CloudWatchLogsClient.create();
        this.snsClient = SnsClient.create();
        this.s3Client = S3Client.create();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.criticalTopicArn = System.getenv("CRITICAL_TOPIC_ARN");
        this.warningTopicArn = System.getenv("WARNING_TOPIC_ARN");
        this.infoTopicArn = System.getenv("INFO_TOPIC_ARN");
        this.alertsBucket = System.getenv("ALERTS_BUCKET");
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            context.getLogger().log("Processing alert event: " + event);
            logEvent("Processing alert event: " + event);
            
            // Parse the alert data
            Map<String, Object> alertData = parseAlertData(event);
            
            // Determine alert severity
            String severity = determineSeverity(alertData);
            
            // Route the alert to the appropriate SNS topic
            String topicArn = routeAlert(severity, alertData);
            
            // Store alert in S3 for audit purposes
            String alertKey = "alerts/" + severity.toLowerCase() + "/" + Instant.now().toString() + "-" + UUID.randomUUID().toString() + ".json";
            storeAlertInS3(alertData, alertKey);
            
            // Log the routing decision
            logEvent("Routed alert to " + severity + " topic: " + topicArn);
            
            return "Successfully routed alert to " + severity + " topic";
        } catch (Exception e) {
            String errorMsg = "Error routing alert: " + e.getMessage();
            context.getLogger().log(errorMsg);
            logEvent(errorMsg);
            throw new RuntimeException("Failed to route alert", e);
        }
    }

    private Map<String, Object> parseAlertData(ScheduledEvent event) {
        Map<String, Object> alertData = new HashMap<>();
        alertData.put("id", UUID.randomUUID().toString());
        alertData.put("timestamp", event.getTime().toString());
        alertData.put("source", event.getSource());
        alertData.put("detailType", "Scheduled Event");
        alertData.put("account", event.getAccount());
        alertData.put("region", event.getRegion());
        alertData.put("resources", event.getResources());
        alertData.put("detail", event.getDetail());
        
        return alertData;
    }

    private String determineSeverity(Map<String, Object> alertData) {
        // In a real implementation, you would analyze the alert data to determine severity
        // For this example, we'll use a simple rule based on the source
        String source = (String) alertData.get("source");
        
        if ("aws.securityhub".equals(source) || "aws.guardduty".equals(source)) {
            return "CRITICAL";
        } else if ("aws.config".equals(source)) {
            return "WARNING";
        } else {
            return "INFO";
        }
    }

    private String routeAlert(String severity, Map<String, Object> alertData) {
        String topicArn;
        switch (severity) {
            case "CRITICAL":
                topicArn = criticalTopicArn;
                break;
            case "WARNING":
                topicArn = warningTopicArn;
                break;
            case "INFO":
            default:
                topicArn = infoTopicArn;
                break;
        }
        
        PublishRequest publishRequest = PublishRequest.builder()
            .topicArn(topicArn)
            .message(alertData.toString())
            .build();
            
        snsClient.publish(publishRequest);
        
        return topicArn;
    }

    private void storeAlertInS3(Map<String, Object> alertData, String key) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
            .bucket(alertsBucket)
            .key(key)
            .build();
            
        s3Client.putObject(putObjectRequest, RequestBody.fromString(alertData.toString()));
    }

    private void logEvent(String message) {
        List<InputLogEvent> logEvents = new ArrayList<>();
        logEvents.add(InputLogEvent.builder()
            .timestamp(System.currentTimeMillis())
            .message(message)
            .build());
            
        PutLogEventsRequest putLogEventsRequest = PutLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamName("alert-router")
            .logEvents(logEvents)
            .build();
            
        logsClient.putLogEvents(putLogEventsRequest);
    }
} 
package me.synapsed.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lambda function for detecting security and operational incidents.
 * Processes events from CloudWatch, Security Hub, and GuardDuty.
 */
public class IncidentDetector implements RequestHandler<ScheduledEvent, String> {
    private final CloudWatchLogsClient logsClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String logGroupName;
    private final String notificationTopicArn;

    public IncidentDetector() {
        this.logsClient = CloudWatchLogsClient.create();
        this.snsClient = SnsClient.create();
        this.objectMapper = new ObjectMapper();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            context.getLogger().log("Processing incident detection event: " + event);
            logEvent("Processing incident detection event: " + event);

            String incidentId = UUID.randomUUID().toString();
            Map<String, Object> incident = Map.of(
                "incidentId", incidentId,
                "timestamp", Instant.now().toString(),
                "source", "cloudwatch",
                "detailType", "ScheduledEvent",
                "detail", event.toString()
            );

            // Store incident in CloudWatch Logs
            logEvent("Detected incident: " + objectMapper.writeValueAsString(incident));

            // Send notification
            sendNotification("Incident detected: " + incidentId + "\nSource: CloudWatch" + 
                           "\nType: ScheduledEvent");

            return "Successfully processed incident detection event";
        } catch (Exception e) {
            String errorMsg = "Error processing incident detection event: " + e.getMessage();
            context.getLogger().log(errorMsg);
            logEvent(errorMsg);
            throw new RuntimeException("Failed to process incident detection event", e);
        }
    }

    private void logEvent(String message) {
        List<InputLogEvent> logEvents = new ArrayList<>();
        logEvents.add(InputLogEvent.builder()
            .timestamp(System.currentTimeMillis())
            .message(message)
            .build());

        PutLogEventsRequest putLogEventsRequest = PutLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamName("incident-detection")
            .logEvents(logEvents)
            .build();

        logsClient.putLogEvents(putLogEventsRequest);
    }

    private void sendNotification(String message) {
        PublishRequest publishRequest = PublishRequest.builder()
            .topicArn(notificationTopicArn)
            .message(message)
            .build();

        snsClient.publish(publishRequest);
    }
} 
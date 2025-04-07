package me.synapsed.aws.lambda;

import java.time.Instant;
import java.util.List;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Lambda function for processing security events from various sources.
 * Logs events to CloudWatch and publishes notifications to SNS.
 */
public class SecurityEventProcessor implements RequestHandler<ScheduledEvent, String> {
    private final CloudWatchLogsClient logsClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String logGroupName;
    private final String notificationTopicArn;

    public SecurityEventProcessor() {
        this.logsClient = CloudWatchLogsClient.builder().build();
        this.snsClient = SnsClient.builder().build();
        this.objectMapper = new ObjectMapper();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            // Log the event
            String eventJson = objectMapper.writeValueAsString(event);
            logEvent("Security event received: " + eventJson);

            // Process the event based on its source
            if (event.getSource().contains("securityhub")) {
                processSecurityHubEvent(event);
            } else if (event.getSource().contains("guardduty")) {
                processGuardDutyEvent(event);
            } else if (event.getSource().contains("config")) {
                processConfigEvent(event);
            }

            return "Success";
        } catch (Exception e) {
            logEvent("Error processing security event: " + e.getMessage());
            throw new RuntimeException("Failed to process security event", e);
        }
    }

    private void processSecurityHubEvent(ScheduledEvent event) throws Exception {
        // Process Security Hub findings
        String message = "Security Hub finding: " + objectMapper.writeValueAsString(event.getDetail());
        logEvent(message);
        publishNotification("Security Hub Finding", message);
    }

    private void processGuardDutyEvent(ScheduledEvent event) throws Exception {
        // Process GuardDuty findings
        String message = "GuardDuty finding: " + objectMapper.writeValueAsString(event.getDetail());
        logEvent(message);
        publishNotification("GuardDuty Finding", message);
    }

    private void processConfigEvent(ScheduledEvent event) throws Exception {
        // Process Config findings
        String message = "Config finding: " + objectMapper.writeValueAsString(event.getDetail());
        logEvent(message);
        publishNotification("Config Finding", message);
    }

    private void logEvent(String message) {
        try {
            PutLogEventsRequest request = PutLogEventsRequest.builder()
                .logGroupName(logGroupName)
                .logStreamName("security-events-" + Instant.now().toString())
                .logEvents(List.of(InputLogEvent.builder()
                    .message(message)
                    .timestamp(Instant.now().toEpochMilli())
                    .build()))
                .build();

            logsClient.putLogEvents(request);
        } catch (Exception e) {
            System.err.println("Failed to log event: " + e.getMessage());
        }
    }

    private void publishNotification(String subject, String message) {
        try {
            PublishRequest request = PublishRequest.builder()
                .topicArn(notificationTopicArn)
                .subject(subject)
                .message(message)
                .build();

            PublishResponse response = snsClient.publish(request);
            logEvent("Published notification: " + response.messageId());
        } catch (Exception e) {
            System.err.println("Failed to publish notification: " + e.getMessage());
        }
    }
} 
package me.synapsed.aws.lambda;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/**
 * Lambda function for managing alert escalations.
 * Monitors alert response times and escalates to higher levels based on severity and time thresholds.
 */
public class EscalationManager implements RequestHandler<SNSEvent, String> {
    private final CloudWatchLogsClient logsClient;
    private final SnsClient snsClient;
    private final String logGroupName;
    private final String escalationTopicArn;
    private final Map<String, Instant> alertStartTimes;

    public EscalationManager() {
        this.logsClient = CloudWatchLogsClient.create();
        this.snsClient = SnsClient.create();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.escalationTopicArn = System.getenv("ESCALATION_TOPIC_ARN");
        this.alertStartTimes = new HashMap<>();
    }

    @Override
    public String handleRequest(SNSEvent event, Context context) {
        try {
            context.getLogger().log("Processing escalation check: " + event.getRecords().size() + " records");
            logEvent("Processing escalation check: " + event.getRecords().size() + " records");
            
            for (SNSEvent.SNSRecord record : event.getRecords()) {
                String message = record.getSNS().getMessage();
                Map<String, SNSEvent.MessageAttribute> messageAttributes = record.getSNS().getMessageAttributes();
                
                // Get alert details
                String alertId = messageAttributes.get("alertId").getValue();
                String severity = messageAttributes.get("severity").getValue();
                
                // Check if this is a new alert
                if (!alertStartTimes.containsKey(alertId)) {
                    alertStartTimes.put(alertId, Instant.now());
                    logEvent("New alert received: " + alertId + " with severity " + severity);
                    continue;
                }
                
                // Check if escalation is needed
                checkAndEscalate(alertId, severity, message);
            }
            
            return "Successfully processed " + event.getRecords().size() + " alerts for escalation";
        } catch (Exception e) {
            String errorMsg = "Error managing escalations: " + e.getMessage();
            context.getLogger().log(errorMsg);
            logEvent(errorMsg);
            throw new RuntimeException("Failed to manage escalations", e);
        }
    }

    private void checkAndEscalate(String alertId, String severity, String message) {
        Instant startTime = alertStartTimes.get(alertId);
        Instant now = Instant.now();
        long minutesSinceAlert = ChronoUnit.MINUTES.between(startTime, now);
        
        // Define escalation thresholds based on severity
        int escalationThreshold;
        switch (severity) {
            case "CRITICAL":
                escalationThreshold = 15; // Escalate after 15 minutes
                break;
            case "WARNING":
                escalationThreshold = 30; // Escalate after 30 minutes
                break;
            case "INFO":
            default:
                escalationThreshold = 60; // Escalate after 60 minutes
                break;
        }
        
        // Check if escalation is needed
        if (minutesSinceAlert >= escalationThreshold) {
            escalateAlert(alertId, severity, minutesSinceAlert, message);
        }
    }

    private void escalateAlert(String alertId, String severity, long minutesSinceAlert, String message) {
        String escalationMessage = String.format(
            "ESCALATION: Alert %s (Severity: %s) has been open for %d minutes without response\nOriginal Alert: %s",
            alertId, severity, minutesSinceAlert, message
        );
        
        // Publish escalation to SNS topic
        PublishRequest publishRequest = PublishRequest.builder()
            .topicArn(escalationTopicArn)
            .message(escalationMessage)
            .messageAttributes(Map.of(
                "severity", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue("ESCALATION")
                    .build(),
                "alertId", software.amazon.awssdk.services.sns.model.MessageAttributeValue.builder()
                    .dataType("String")
                    .stringValue(alertId)
                    .build()
            ))
            .build();
            
        snsClient.publish(publishRequest);
        
        // Log the escalation
        logEvent("Escalated alert " + alertId + " after " + minutesSinceAlert + " minutes");
    }

    private void logEvent(String message) {
        List<InputLogEvent> logEvents = new ArrayList<>();
        logEvents.add(InputLogEvent.builder()
            .timestamp(System.currentTimeMillis())
            .message(message)
            .build());
            
        PutLogEventsRequest putLogEventsRequest = PutLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamName("escalation-manager")
            .logEvents(logEvents)
            .build();
            
        logsClient.putLogEvents(putLogEventsRequest);
    }
} 
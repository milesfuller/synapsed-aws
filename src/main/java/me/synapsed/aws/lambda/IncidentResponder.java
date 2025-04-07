package me.synapsed.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
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
 * Lambda function for responding to and recovering from incidents.
 * Implements automated response actions and recovery procedures.
 */
public class IncidentResponder implements RequestHandler<Map<String, Object>, String> {
    private final CloudWatchLogsClient logsClient;
    private final SnsClient snsClient;
    private final ObjectMapper objectMapper;
    private final String logGroupName;
    private final String notificationTopicArn;

    public IncidentResponder() {
        this.logsClient = CloudWatchLogsClient.create();
        this.snsClient = SnsClient.create();
        this.objectMapper = new ObjectMapper();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.notificationTopicArn = System.getenv("NOTIFICATION_TOPIC_ARN");
    }

    @Override
    public String handleRequest(Map<String, Object> incident, Context context) {
        try {
            context.getLogger().log("Processing incident response: " + incident);
            logEvent("Processing incident response: " + incident);

            String responseId = UUID.randomUUID().toString();
            Map<String, Object> response = Map.of(
                "responseId", responseId,
                "incidentId", incident.get("incidentId"),
                "timestamp", Instant.now().toString(),
                "status", "IN_PROGRESS"
            );

            // Log response initiation
            logEvent("Initiating incident response: " + objectMapper.writeValueAsString(response));

            // Determine response actions based on incident type
            String incidentType = (String) incident.get("detailType");
            if (incidentType != null) {
                switch (incidentType) {
                    case "Security Hub Findings":
                        handleSecurityHubIncident(incident, response);
                        break;
                    case "GuardDuty Finding":
                        handleGuardDutyIncident(incident, response);
                        break;
                    default:
                        handleGenericIncident(incident, response);
                }
            }

            // Update response status
            response = Map.of(
                "responseId", responseId,
                "incidentId", incident.get("incidentId"),
                "timestamp", Instant.now().toString(),
                "status", "COMPLETED"
            );

            // Log response completion
            logEvent("Completed incident response: " + objectMapper.writeValueAsString(response));

            // Send notification
            sendNotification("Incident response completed: " + responseId + 
                           "\nIncident: " + incident.get("incidentId") + 
                           "\nStatus: COMPLETED");

            return "Successfully processed incident response";
        } catch (Exception e) {
            String errorMsg = "Error processing incident response: " + e.getMessage();
            context.getLogger().log(errorMsg);
            logEvent(errorMsg);
            throw new RuntimeException("Failed to process incident response", e);
        }
    }

    private void handleSecurityHubIncident(Map<String, Object> incident, Map<String, Object> response) {
        logEvent("Handling Security Hub incident: " + incident.get("incidentId"));
        // Implement Security Hub specific response actions
        // For example: isolate affected resources, apply security patches, etc.
    }

    private void handleGuardDutyIncident(Map<String, Object> incident, Map<String, Object> response) {
        logEvent("Handling GuardDuty incident: " + incident.get("incidentId"));
        // Implement GuardDuty specific response actions
        // For example: block suspicious IPs, terminate compromised instances, etc.
    }

    private void handleGenericIncident(Map<String, Object> incident, Map<String, Object> response) {
        logEvent("Handling generic incident: " + incident.get("incidentId"));
        // Implement generic response actions
        // For example: scale resources, restart services, etc.
    }

    private void logEvent(String message) {
        List<InputLogEvent> logEvents = new ArrayList<>();
        logEvents.add(InputLogEvent.builder()
            .timestamp(System.currentTimeMillis())
            .message(message)
            .build());

        PutLogEventsRequest putLogEventsRequest = PutLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamName("incident-response")
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
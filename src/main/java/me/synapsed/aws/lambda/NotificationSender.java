package me.synapsed.aws.lambda;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;

import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;

/**
 * Lambda function for sending notifications via email and other channels.
 * Processes SNS messages and sends notifications to the appropriate recipients.
 */
public class NotificationSender implements RequestHandler<SNSEvent, String> {
    private final CloudWatchLogsClient logsClient;
    private final SesClient sesClient;
    private final String logGroupName;
    private final String senderEmail;

    public NotificationSender() {
        this.logsClient = CloudWatchLogsClient.create();
        this.sesClient = SesClient.create();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.senderEmail = System.getenv("SENDER_EMAIL");
    }

    @Override
    public String handleRequest(SNSEvent event, Context context) {
        try {
            context.getLogger().log("Processing notification event: " + event.getRecords().size() + " records");
            logEvent("Processing notification event: " + event.getRecords().size() + " records");
            
            for (SNSEvent.SNSRecord record : event.getRecords()) {
                String message = record.getSNS().getMessage();
                Map<String, SNSEvent.MessageAttribute> messageAttributes = record.getSNS().getMessageAttributes();
                
                // Get severity and alert ID from message attributes
                String severity = messageAttributes.get("severity").getValue();
                String alertId = messageAttributes.get("alertId").getValue();
                
                // Send email notification
                sendEmailNotification(severity, message, alertId);
                
                // Log the notification
                logEvent("Sent " + severity + " notification for alert " + alertId);
            }
            
            return "Successfully processed " + event.getRecords().size() + " notifications";
        } catch (Exception e) {
            String errorMsg = "Error sending notification: " + e.getMessage();
            context.getLogger().log(errorMsg);
            logEvent(errorMsg);
            throw new RuntimeException("Failed to send notification", e);
        }
    }

    private void sendEmailNotification(String severity, String message, String alertId) {
        // Create email content based on severity
        String subject = "[" + severity + "] Synapsed Alert: " + alertId;
        String bodyText = "Alert Details:\n" +
                         "Severity: " + severity + "\n" +
                         "Alert ID: " + alertId + "\n" +
                         "Time: " + Instant.now().toString() + "\n\n" +
                         "Message:\n" + message;
        
        // Create email message
        Content subjectContent = Content.builder().data(subject).charset("UTF-8").build();
        Content bodyContent = Content.builder().data(bodyText).charset("UTF-8").build();
        Body body = Body.builder().text(bodyContent).build();
        Message emailMessage = Message.builder().subject(subjectContent).body(body).build();
        
        // Create destination
        Destination destination = Destination.builder()
            .toAddresses("security-team@synapsed.com")
            .build();
        
        // Send email
        SendEmailRequest sendEmailRequest = SendEmailRequest.builder()
            .source(senderEmail)
            .destination(destination)
            .message(emailMessage)
            .build();
            
        sesClient.sendEmail(sendEmailRequest);
    }

    private void logEvent(String message) {
        List<InputLogEvent> logEvents = new ArrayList<>();
        logEvents.add(InputLogEvent.builder()
            .timestamp(System.currentTimeMillis())
            .message(message)
            .build());
            
        PutLogEventsRequest putLogEventsRequest = PutLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamName("notification-sender")
            .logEvents(logEvents)
            .build();
            
        logsClient.putLogEvents(putLogEventsRequest);
    }
} 
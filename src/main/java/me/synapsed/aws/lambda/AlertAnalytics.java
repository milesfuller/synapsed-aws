package me.synapsed.aws.lambda;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;

import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Lambda function for generating alert analytics and metrics.
 * Processes alert data from S3 and publishes metrics to CloudWatch.
 */
public class AlertAnalytics implements RequestHandler<ScheduledEvent, String> {
    private final CloudWatchClient cloudWatchClient;
    private final CloudWatchLogsClient logsClient;
    private final S3Client s3Client;
    private final String logGroupName;
    private final String alertsBucket;

    public AlertAnalytics() {
        this.cloudWatchClient = CloudWatchClient.create();
        this.logsClient = CloudWatchLogsClient.create();
        this.s3Client = S3Client.create();
        this.logGroupName = System.getenv("LOG_GROUP_NAME");
        this.alertsBucket = System.getenv("ALERTS_BUCKET");
    }

    @Override
    public String handleRequest(ScheduledEvent event, Context context) {
        try {
            context.getLogger().log("Starting alert analytics generation");
            logEvent("Starting alert analytics generation");
            
            // Get alerts from the last 24 hours
            Instant endTime = Instant.now();
            Instant startTime = endTime.minus(24, ChronoUnit.HOURS);
            
            // List objects in the alerts bucket
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(alertsBucket)
                .build();
                
            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            
            // Process alerts and generate metrics
            Map<String, Integer> severityCounts = new HashMap<>();
            Map<String, Integer> sourceCounts = new HashMap<>();
            int totalAlerts = 0;
            
            for (S3Object object : listResponse.contents()) {
                // Skip objects older than 24 hours
                if (object.lastModified().isAfter(startTime)) {
                    continue;
                }
                
                // Count alerts by severity and source
                String key = object.key();
                if (key.startsWith("alerts/")) {
                    String[] parts = key.split("/");
                    if (parts.length >= 2) {
                        String severity = parts[1].toUpperCase();
                        severityCounts.merge(severity, 1, Integer::sum);
                        
                        // Extract source from the alert data
                        String source = extractSourceFromAlert(object);
                        if (source != null) {
                            sourceCounts.merge(source, 1, Integer::sum);
                        }
                        
                        totalAlerts++;
                    }
                }
            }
            
            // Publish metrics to CloudWatch
            publishMetrics(totalAlerts, severityCounts, sourceCounts);
            
            // Log analytics completion
            logEvent("Completed alert analytics generation. Total alerts: " + totalAlerts);
            
            return "Successfully generated alert analytics";
        } catch (Exception e) {
            String errorMsg = "Error generating alert analytics: " + e.getMessage();
            context.getLogger().log(errorMsg);
            logEvent(errorMsg);
            throw new RuntimeException("Failed to generate alert analytics", e);
        }
    }

    private String extractSourceFromAlert(S3Object object) {
        // In a real implementation, you would read and parse the alert data from S3
        // For this example, we'll return a placeholder
        return "aws.securityhub";
    }

    private void publishMetrics(int totalAlerts, Map<String, Integer> severityCounts, Map<String, Integer> sourceCounts) {
        List<MetricDatum> metrics = new ArrayList<>();
        
        // Add total alerts metric
        metrics.add(MetricDatum.builder()
            .metricName("TotalAlerts")
            .value((double) totalAlerts)
            .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
            .timestamp(Instant.now())
            .build());
            
        // Add severity metrics
        for (Map.Entry<String, Integer> entry : severityCounts.entrySet()) {
            metrics.add(MetricDatum.builder()
                .metricName("AlertsBySeverity")
                .value((double) entry.getValue())
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .timestamp(Instant.now())
                .dimensions(Dimension.builder()
                    .name("Severity")
                    .value(entry.getKey())
                    .build())
                .build());
        }
        
        // Add source metrics
        for (Map.Entry<String, Integer> entry : sourceCounts.entrySet()) {
            metrics.add(MetricDatum.builder()
                .metricName("AlertsBySource")
                .value((double) entry.getValue())
                .unit(software.amazon.awssdk.services.cloudwatch.model.StandardUnit.COUNT)
                .timestamp(Instant.now())
                .dimensions(Dimension.builder()
                    .name("Source")
                    .value(entry.getKey())
                    .build())
                .build());
        }
        
        // Publish metrics to CloudWatch
        PutMetricDataRequest putMetricDataRequest = PutMetricDataRequest.builder()
            .namespace("Synapsed/Alerts")
            .metricData(metrics)
            .build();
            
        cloudWatchClient.putMetricData(putMetricDataRequest);
    }

    private void logEvent(String message) {
        List<InputLogEvent> logEvents = new ArrayList<>();
        logEvents.add(InputLogEvent.builder()
            .timestamp(System.currentTimeMillis())
            .message(message)
            .build());
            
        PutLogEventsRequest putLogEventsRequest = PutLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamName("alert-analytics")
            .logEvents(logEvents)
            .build();
            
        logsClient.putLogEvents(putLogEventsRequest);
    }
} 
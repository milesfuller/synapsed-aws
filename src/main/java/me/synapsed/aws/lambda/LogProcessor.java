package me.synapsed.aws.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent;
import com.amazonaws.services.lambda.runtime.events.KinesisEvent.KinesisEventRecord;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.Record;
import software.amazon.awssdk.core.SdkBytes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public class LogProcessor implements RequestHandler<KinesisEvent, Void> {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final FirehoseClient firehoseClient = FirehoseClient.create();
    private static final String DELIVERY_STREAM_NAME = System.getenv("DELIVERY_STREAM_NAME");

    @Override
    public Void handleRequest(KinesisEvent event, Context context) {
        List<KinesisEventRecord> records = event.getRecords();
        context.getLogger().log("Processing " + records.size() + " records");

        for (KinesisEventRecord record : records) {
            try {
                // Extract log data from Kinesis record
                String data = new String(record.getKinesis().getData().array(), StandardCharsets.UTF_8);
                ObjectNode logEntry = (ObjectNode) objectMapper.readTree(data);

                // Add metadata
                logEntry.put("timestamp", Instant.now().toString());
                logEntry.put("source", "kinesis");
                logEntry.put("processor_id", context.getAwsRequestId());

                // Forward to Firehose
                PutRecordRequest putRecordRequest = PutRecordRequest.builder()
                    .deliveryStreamName(DELIVERY_STREAM_NAME)
                    .record(Record.builder()
                        .data(SdkBytes.fromString(objectMapper.writeValueAsString(logEntry), StandardCharsets.UTF_8))
                        .build())
                    .build();

                firehoseClient.putRecord(putRecordRequest);
                context.getLogger().log("Successfully processed record: " + record.getKinesis().getSequenceNumber());

            } catch (Exception e) {
                context.getLogger().log("Error processing record: " + e.getMessage());
            }
        }

        return null;
    }
} 
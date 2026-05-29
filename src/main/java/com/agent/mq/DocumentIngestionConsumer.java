package com.agent.mq;

import com.agent.core.Document;
import com.agent.ingestion.FullIngestionPipeline;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;

@Slf4j
@Component
@ConditionalOnProperty(value = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = "document-ingestion-topic",
        consumerGroup = "ingestion-consumer-group",
        consumeMode = ConsumeMode.ORDERLY,
        maxReconsumeTimes = 3
)
public class DocumentIngestionConsumer implements RocketMQListener<DocumentIngestionMessage> {

    private static final int MAX_RETRIES = 3;
    private static final String DLQ_TOPIC = "document-ingestion-dlq-topic";

    private final FullIngestionPipeline ingestionPipeline;
    private final IngestionStatusStore statusStore;
    private final RocketMQTemplate rocketMQTemplate;

    public DocumentIngestionConsumer(FullIngestionPipeline ingestionPipeline,
                                     IngestionStatusStore statusStore,
                                     RocketMQTemplate rocketMQTemplate) {
        this.ingestionPipeline = ingestionPipeline;
        this.statusStore = statusStore;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void onMessage(DocumentIngestionMessage message) {
        String documentId = message.getDocumentId();
        int retryCount = message.getRetryCount();

        log.info("RocketMQ consumer received: documentId={}, fileName={}, retryCount={}",
                documentId, message.getFileName(), retryCount);

        statusStore.update(documentId, retryCount > 0 ? "RETRYING(" + retryCount + ")" : "PROCESSING", null);

        try {
            Path filePath = Path.of(message.getFilePath());
            if (!filePath.toFile().exists()) {
                handleFailure(message, "File not found: " + message.getFilePath());
                return;
            }

            Document document = new Document(documentId, message.getFileName(), message.getFileType());
            document.setFileSize(message.getFileSize());
            document.setUploadedAt(Instant.ofEpochMilli(message.getCreatedAt()));

            document = ingestionPipeline.ingestToEs(document, filePath, status -> {
                statusStore.update(documentId, status.name(), null);
            });

            log.info("RocketMQ ingestion completed: documentId={}, chunks={}, status={}",
                    documentId, document.getChunkCount(), document.getStatus());
            statusStore.update(documentId, "READY", document.getChunkCount());

        } catch (Exception e) {
            handleFailure(message, e.getMessage());
        }
    }

    private void handleFailure(DocumentIngestionMessage message, String reason) {
        String documentId = message.getDocumentId();
        int retryCount = message.getRetryCount();

        statusStore.recordRetryAttempt(documentId, retryCount, reason);

        if (retryCount < MAX_RETRIES) {
            log.warn("Ingestion failed (retry {}/{}), will retry: documentId={}, reason={}",
                    retryCount + 1, MAX_RETRIES, documentId, reason);

            DocumentIngestionMessage retryMessage = message.withIncrementedRetry();
            rocketMQTemplate.syncSend("document-ingestion-topic", retryMessage, 3000);
        } else {
            log.error("Ingestion failed after {} retries, moving to DLQ: documentId={}, reason={}",
                    MAX_RETRIES, documentId, reason);

            rocketMQTemplate.syncSend(DLQ_TOPIC, message, 3000);

            statusStore.markDeadLettered(documentId, reason);
        }
    }
}
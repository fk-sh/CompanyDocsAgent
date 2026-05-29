package com.agent.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(value = "rocketmq.name-server")
@RocketMQMessageListener(
        topic = "document-ingestion-dlq-topic",
        consumerGroup = "ingestion-dlq-consumer-group",
        maxReconsumeTimes = 0
)
public class DocumentIngestionDlqConsumer implements RocketMQListener<DocumentIngestionMessage> {

    private final IngestionStatusStore statusStore;

    public DocumentIngestionDlqConsumer(IngestionStatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public void onMessage(DocumentIngestionMessage message) {
        String documentId = message.getDocumentId();
        String reason = "Exhausted " + (message.getRetryCount() + 1) + " ingestion attempts, moved to dead letter queue";

        log.error("=== DEAD LETTER QUEUE === documentId={}, fileName={}, retryCount={}",
                documentId, message.getFileName(), message.getRetryCount());

        statusStore.markDeadLettered(documentId, reason);

        log.error("Document {} permanently failed ingestion. Manual retry required via POST /api/v1/documents/{}/retry",
                documentId, documentId);
    }
}
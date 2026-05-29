package com.agent.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;

@Slf4j
public class RocketMQDocumentIngestionProducer implements DocumentIngestionProducer {

    private static final String TOPIC = "document-ingestion-topic";

    private final RocketMQTemplate rocketMQTemplate;

    public RocketMQDocumentIngestionProducer(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public boolean send(DocumentIngestionMessage message) {
        try {
            SendResult result = rocketMQTemplate.syncSend(TOPIC, message, 3000);
            if (result.getSendStatus() == SendStatus.SEND_OK) {
                log.info("RocketMQ message sent: documentId={}, msgId={}",
                        message.getDocumentId(), result.getMsgId());
                return true;
            }
            log.error("RocketMQ send failed: documentId={}, status={}",
                    message.getDocumentId(), result.getSendStatus());
            return false;
        } catch (Exception e) {
            log.error("RocketMQ send exception: documentId={}", message.getDocumentId(), e);
            return false;
        }
    }
}
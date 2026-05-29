package com.agent.mq;

public interface DocumentIngestionProducer {

    boolean send(DocumentIngestionMessage message);
}
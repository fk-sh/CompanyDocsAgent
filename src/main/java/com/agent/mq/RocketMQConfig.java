package com.agent.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class RocketMQConfig {

    @Bean
    @ConditionalOnProperty(value = "rocketmq.name-server")
    public DocumentIngestionProducer rocketMQProducer(RocketMQTemplate rocketMQTemplate) {
        log.info("RocketMQ available, enabling async document ingestion via MQ");
        return new RocketMQDocumentIngestionProducer(rocketMQTemplate);
    }
}
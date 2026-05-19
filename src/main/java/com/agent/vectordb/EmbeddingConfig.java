package com.agent.vectordb;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Embedding 服务配置类，从 {@code application.yml} 中 {@code embedding.*} 前缀读取配置。
 * <p>
 * 同时创建 {@link WebClient} Bean（Bean 名为 {@code embeddingWebClient}），
 * 用于调用 OpenAI 兼容的 Embedding API。
 */
@Configuration
@ConfigurationProperties(prefix = "embedding")
@Getter
@Setter
public class EmbeddingConfig {

    /** Embedding API 地址（如 http://localhost:8088） */
    private String baseUrl;

    /** 模型名称（如 bge-large-zh-v1.5） */
    private String modelName;

    /** 向量维度，默认 1024 */
    private int dimension = 1024;

    /** 批量向量化的每批大小，默认 32 */
    private int batchSize = 32;

    /** Redis 缓存 TTL，默认 24h */
    private Duration cacheTtl = Duration.ofHours(24);

    /**
     * 创建 Embedding API 专用的 WebClient。
     * 设置 10MB 内存上限以容纳大批量向量响应的 JSON。
     */
    @Bean
    public WebClient embeddingWebClient() {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}

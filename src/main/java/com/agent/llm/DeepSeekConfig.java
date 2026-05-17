package com.agent.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * DeepSeek LLM 配置类，通过 OpenAI 兼容接口接入 DeepSeek V4-Pro。
 * <p>
 * 配置项从 {@code application-dev.yml} 的 {@code deepseek.*} 前缀读取，
 * 通过环境变量 {@code DEEPSEEK_API_KEY} 注入 API Key。
 * <p>
 * 仅在配置了 {@code deepseek.base-url} 时激活，
 * 否则由 LangChain4j 自动配置接管。
 */
@Configuration
@ConfigurationProperties(prefix = "deepseek")
@ConditionalOnProperty(prefix = "deepseek", name = "base-url")
@Getter
@Setter
public class DeepSeekConfig {

    /** DeepSeek API 地址（OpenAI 兼容） */
    private String baseUrl;

    /** API 密钥 */
    private String apiKey;

    /** 模型名称 */
    private String modelName;

    /** 生成温度（0~2，越高越随机） */
    private double temperature;

    /** 最大输出 Token 数 */
    private int maxTokens;

    /**
     * 同步聊天模型 Bean。
     * 基于 DeepSeek API 的 OpenAI 兼容接口。
     */
    @Bean
    @Primary//当存在多个 ChatLanguageModel 实现时，优先注入此 Bean。但目前其实是冗余的
    public ChatLanguageModel chatLanguageModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * 流式聊天模型 Bean。
     * 基于 DeepSeek API 的 OpenAI 兼容流式接口。
     */
    @Bean
    @Primary
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(120))
                .build();
    }
}

package com.agent.service;

import com.agent.llm.DeepSeekChatClient;
import com.agent.llm.DeepSeekStreamingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 最简问答服务，Phase 3 的验证入口。
 * <p>
 * 直接透传用户输入给 DeepSeek，不涉及检索、Prompt 拼接、上下文管理等高级功能。
 * 后续 Phase 8 将被 {@code ChatService} 取代（引入多 Agent 编排）。
 */
@Slf4j
@Service
public class SimpleChatService {

    private final DeepSeekChatClient chatClient;
    private final DeepSeekStreamingClient streamingClient;

    public SimpleChatService(DeepSeekChatClient chatClient, DeepSeekStreamingClient streamingClient) {
        this.chatClient = chatClient;
        this.streamingClient = streamingClient;
    }

    /**
     * 同步对话。
     *
     * @param userMessage 用户输入
     * @return LLM 回复
     */
    public String chat(String userMessage) {
        log.info("sync chat: {}", userMessage);
        return chatClient.chat(userMessage);
    }

    /**
     * 流式对话（SSE）。
     *
     * @param userMessage 用户输入
     * @return 流式输出的 Flux
     */
    public Flux<String> stream(String userMessage) {
        log.info("stream chat: {}", userMessage);
        return streamingClient.stream(userMessage);
    }
}

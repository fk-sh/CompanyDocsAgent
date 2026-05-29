package com.agent.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * DeepSeek 流式聊天客户端，封装 LangChain4j 的 {@link StreamingChatLanguageModel}。
 * <p>
 * 客户端进行提问走的就是这个接口
 * 通过 {@link Sinks.Many} 将 LangChain4j 的回调式流式输出桥接到 Project Reactor 的 {@link Flux}，
 * 供上层通过 SSE 推送给前端。
 */
@Slf4j
@Component
public class DeepSeekStreamingClient {

    private final StreamingChatLanguageModel model;

    public DeepSeekStreamingClient(StreamingChatLanguageModel model) {
        this.model = model;
    }

    /**
     * 流式单轮对话。
     *
     * @param userMessage 用户输入
     * @return 流式输出的 Flux
     */
    public Flux<String> stream(String userMessage) {
        return stream(null, userMessage);
    }

    /**
     * 流式多轮对话。
     *
     * @param systemPrompt 系统提示词（可为 null）
     * @param userMessage  用户输入
     * @return 流式输出的 Flux
     */
    public Flux<String> stream(String systemPrompt, String userMessage) {
        log.debug("stream request: {}", userMessage);
        var messages = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userMessage));
        return doStream(messages);
    }

    public Flux<String> streamRaw(String prompt) {
        log.debug("streamRaw request, prompt length: {}", prompt.length());
        var messages = java.util.List.<dev.langchain4j.data.message.ChatMessage>of(
                UserMessage.from(prompt));
        return doStream(messages);
    }

    public Flux<String> streamRaw(String systemPrompt, String userPrompt) {
        log.debug("streamRaw request with system prompt, user prompt length: {}", userPrompt.length());
        var messages = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userPrompt));
        return doStream(messages);
    }

    private Flux<String> doStream(java.util.List<dev.langchain4j.data.message.ChatMessage> messages) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        model.generate(messages, new dev.langchain4j.model.StreamingResponseHandler<>() {
            @Override
            public void onNext(String token) {
                sink.tryEmitNext(token);
            }

            @Override
            public void onComplete(dev.langchain4j.model.output.Response<AiMessage> response) {
                log.debug("stream complete");
                sink.tryEmitComplete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("stream error", error);
                sink.tryEmitError(error);
            }
        });

        return sink.asFlux();
    }

}

package com.agent.llm;

import com.agent.core.Message;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DeepSeek 同步聊天客户端，封装 LangChain4j 的 {@link ChatLanguageModel}。
 * <p>
 * Agent内部进行传递时使用这个方法
 * 直接就返回整个文本传递给其他Agent，如果用流式输出，那么还要将它们拼好后再传递给其他Agent。
 * 提供文本级单轮/多轮对话能力，作为上层 Agent 调用 LLM 的统一入口。
 * 不关心 Prompt 构建和上下文拼装——这些由上层组件负责。
 */
@Slf4j
@Component
public class DeepSeekChatClient {

    private final ChatLanguageModel model;

    public DeepSeekChatClient(ChatLanguageModel model) {
        this.model = model;
    }

    /**
     * 单轮对话：发消息，收回复。
     *
     * @param userMessage 用户输入
     * @return LLM 回复文本
     */
    public String chat(String userMessage) {
        log.debug("chat request: {}", userMessage);
        String response = model.generate(userMessage);
        log.debug("chat response: {}", response);
        return response;
    }

    /**
     * 带系统指令的单轮对话。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户输入
     * @return LLM 回复文本
     */
    public String chat(String systemPrompt, String userMessage) {
        log.debug("chat with system prompt, user: {}", userMessage);
        String response = model.generate(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage)
        ).content().text();
        log.debug("chat response: {}", response);
        return response;
    }

    /**
     * 多轮对话，传入完整消息历史。
     *
     * @param messages 消息列表（含 SYSTEM / USER / ASSISTANT 角色）
     * @return LLM 回复文本
     */
    public String chat(List<Message> messages) {
        List<dev.langchain4j.data.message.ChatMessage> langchainMessages = messages.stream()
                .map(this::toLangChainMessage)//将自定义的 Message 转换成 LangChain4j 的 ChatMessage
                .toList();
        log.debug("chat with {} messages", langchainMessages.size());
        String response = model.generate(langchainMessages).content().text();
        log.debug("chat response: {}", response);
        return response;
    }

    //把我们自定义的 Message 对象 转换成 LangChain4j 的 ChatMessage 类型 。
    //我们定义了 com.agent.core.Message （项目自己的模型），而 LangChain4j 不认识它，只能用 dev.langchain4j.data.message.* 。当 DeepSeekChatClient.chat(List<Message>) 收到消息列表后，必须逐条翻译成 LangChain4j 能消费的格式
    private dev.langchain4j.data.message.ChatMessage toLangChainMessage(Message msg) {
        return switch (msg.getRole()) {
            case SYSTEM -> SystemMessage.from(msg.getContent());
            case USER -> UserMessage.from(msg.getContent());
            case ASSISTANT -> AiMessage.from(msg.getContent());
            case TOOL -> UserMessage.from("[Tool Result] " + msg.getContent());
        };
    }

}

package com.agent.memory;

import com.agent.core.Memory;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 短期记忆实现，管理当前会话的多轮对话历史。
 * <p>
 * 采用<b>滑动窗口 + Token 预算 + LLM 压缩</b>三层机制：
 * <ol>
 *   <li><b>滑动窗口</b>：{@link LinkedList} 存储消息，新消息从尾部追加，
 *       Token 超限时从头部移除最旧消息，保留最多 30 条</li>
 *   <li><b>Token 预算</b>：每次 add 时累加 Token 计数，超过 {@link #maxTokens}（默认 4096）
 *       时自动调用 {@link #ensureWithinLimit()} 驱逐旧消息</li>
 *   <li><b>LLM 压缩</b>：{@link #compressOldMessages()} 将前半段旧对话送给大模型生成摘要，
 *       存入 {@link #compressedSummary}，后续 {@link #buildContextPrompt()} 拼接时用摘要替代原始消息，
 *       既节省 Token 又保留关键信息</li>
 * </ol>
 * <p>
 * 生命周期：单次 Session，{@link #clear()} 后重新开始。
 * 持久化：通过 {@link MysqlMessageStore} 异步写入 MySQL。
 *
 * @see Memory
 * @see MemoryManager
 */
@Slf4j
@Component
public class ConversationMemory implements Memory {

    /**
     * 送给 LLM 做压缩的 Prompt 模板。核心约束：
     * 保留关键信息/意图/事实/结论，控制在 200 字以内。
     */
    private static final String COMPRESSION_PROMPT = """
            你是一个对话摘要助手。请将以下对话历史压缩为一段简洁的摘要，保留关键信息、
            用户意图、重要事实和结论。摘要应控制在200字以内。

            对话历史：
            %s

            摘要：
            """;

    /** 滑动窗口最大消息条数，超出后旧消息被驱逐 */
    @Getter
    private int maxWindowSize = 30;

    /** Token 预算上限，默认 4096（DeepSeek 单轮上限） */
    @Getter
    @Setter
    private int maxTokens = 4096;

    /** 当前已消耗的 Token 估算数 */
    @Getter
    private int tokensUsed = 0;

    /**
     * 压缩后的对话摘要。非 null 时 {@link #buildContextPrompt()} 会优先拼接摘要，
     * 从而用很小的 Token 成本保留早期对话的关键信息。
     */
    @Getter
    private String compressedSummary;

    /**
     * 消息队列，用 LinkedList 而不是 ArrayList，
     * 因为滑动窗口需要高效地从头部移除（removeFirst = O(1)）。
     */
    private final LinkedList<Message> messages = new LinkedList<>();

    private final DeepSeekChatClient chatClient;
    private final MysqlMessageStore messageStore;

    public ConversationMemory(DeepSeekChatClient chatClient, MysqlMessageStore messageStore) {
        this.chatClient = chatClient;
        this.messageStore = messageStore;
    }

    @Override
    public MemoryType type() {
        return MemoryType.SHORT_TERM;
    }

    /**
     * 追加一条消息到队列尾部。
     * 追加后自动累加 Token 并检查是否需要驱逐旧消息。
     */
    @Override
    public void add(Message message) {
        messages.addLast(message);
        int msgTokens = estimateTokens(message.getContent());//估算新消息的 Token 数
        tokensUsed += msgTokens;//累加新消息的 Token 数
        ensureWithinLimit();//检查是否需要驱逐旧消息，超过上限时调用 compressOldMessages()，压缩旧消息
    }

    @Override
    public void addAll(List<Message> messages) {
        for (Message msg : messages) {
            add(msg);
        }
    }

    /**
     * 获取最近 N 条消息（从尾部取，即时间上最新的）。
     */
    @Override
    public List<Message> getRecent(int count) {
        int size = messages.size();
        if (count >= size) {
            return new ArrayList<>(messages);
        }
        List<Message> result = new ArrayList<>(count);
        int start = size - count;
        for (int i = start; i < size; i++) {
            result.add(messages.get(i));
        }
        return result;
    }

    @Override
    public List<Message> getAll() {
        return new ArrayList<>(messages);
    }

    /**
     * 触发内存压缩：重新设置 Token 上限并驱逐超出的旧消息。
     * 不会调用 LLM 压缩，只做内存淘汰。
     */
    @Override
    public void compact(int maxTokensThreshold) {
        this.maxTokens = maxTokensThreshold;
        ensureWithinLimit();
    }

    /**
     * 清空当前会话的所有消息、Token 数和压缩摘要。
     * 用于切换会话或重置内存。
     */
    @Override
    public void clear() {
        messages.clear();
        tokensUsed = 0;
        compressedSummary = null;
    }

    /**
     * 将单条消息异步持久化到 MySQL。
     * 调用 MysqlMessageStore 的 @Async 方法，不阻塞当前线程。
     */
    public void persistToDb(String sessionId, Message message) {
        try {
            messageStore.saveAsync(sessionId, message);
        } catch (Exception e) {
            log.warn("Failed to persist message to MySQL: {}", e.getMessage());
        }
    }

    /**
     * 从 MySQL 加载历史消息并恢复到当前内存。
     * 典型场景：用户返回已存在的 session 时，恢复之前的对话上下文。
     */
    public void loadFromDb(String sessionId, int limit) {
        List<Message> dbMessages = messageStore.findBySessionId(sessionId, limit);
        if (!dbMessages.isEmpty()) {
            clear();
            addAll(dbMessages);
            log.debug("Loaded {} messages from DB for session {}", dbMessages.size(), sessionId);
        }
    }

    /**
     * Token 超限时循环驱逐头部（最旧）消息，直到回到预算内。
     * 至少保留 2 条消息（1 问 1 答），避免清空。
     */
    private void ensureWithinLimit() {
        while (tokensUsed > maxTokens && messages.size() > 2) {
            Message oldest = messages.removeFirst();
            tokensUsed -= estimateTokens(oldest.getContent());//减去最旧消息的 Token 数
            log.debug("Evicted oldest message, tokensUsed={}/{}", tokensUsed, maxTokens);
        }
    }

    /**
     * 将前半段旧消息送给 LLM 生成压缩摘要。
     * 仅在消息数超过 10 条时触发（太少没必要压缩）。
     * <p>
     * 压缩策略：取前半段（messages[0..mid]）进行压缩，后半段保留原始消息。
     * 这样 LLM 可以在摘要中记住早期信息，同时保留近期对话的完整细节。
     */
    public void compressOldMessages() {
        if (messages.size() <= 10) {//如果消息数小于等于 10 条，没必要压缩
            return;
        }

        //消息大于 10 条，需要压缩
        //取消息半数作为压缩分界点
        int splitIndex = messages.size() / 2;
        List<Message> oldMessages = new ArrayList<>(messages.subList(0, splitIndex));//取前半段消息

        //将历史压缩摘要和当前滑动窗口中的完整消息拼接成一个字符串，用于注入 LLM 的上下文
        StringBuilder historyText = new StringBuilder();
        if (compressedSummary != null && !compressedSummary.isEmpty()) {
            historyText.append("【历史对话摘要】\n");
            historyText.append(compressedSummary).append("\n\n");
        }

        if (!messages.isEmpty()) {
            historyText.append("【近期对话】\n");
            for (Message msg : messages) {
                historyText.append("[").append(msg.getRole()).append("]: ")
                        .append(msg.getContent()).append("\n");
            }
        }

        try {
            String prompt = String.format(COMPRESSION_PROMPT, historyText.toString());
            String summary = chatClient.chat(prompt);
            //将新摘要拼接到旧摘要后面
            compressedSummary = summary;//更新压缩摘要
            
            log.debug("Compressed {} messages into summary: {}", oldMessages.size(), summary);
        } catch (Exception e) {
            log.warn("Compression failed: {}", e.getMessage());
        }
    }

    /**
     * 构建可用于注入 LLM 的上下文 Prompt。
     * <p>
     * 拼接顺序：
     * <ol>
     *   <li>【历史对话摘要】— 压缩摘要（如果有的话）</li>
     *   <li>【近期对话】— 当前滑动窗口中的完整消息</li>
     * </ol>
     * 这样 LLM 同时看到摘要（节省 Token）和近期细节（保留精度）。
     *
     * @return 格式化的上下文字符串，可能为空
     */
    //将压缩摘要和当前滑动窗口中的完整消息拼接成一个字符串，用于注入 LLM 的上下文
    public String buildContextPrompt() {
        StringBuilder sb = new StringBuilder();

        if (compressedSummary != null && !compressedSummary.isEmpty()) {
            sb.append("【历史对话摘要】\n");
            sb.append(compressedSummary).append("\n\n");
        }

        if (!messages.isEmpty()) {
            sb.append("【近期对话】\n");
            for (Message msg : messages) {
                sb.append("[").append(msg.getRole()).append("]: ")
                        .append(msg.getContent()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 估算文本 Token 数。中文场景 1 Token ≈ 3.5 字符。
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / 3.5);
    }

    public int size() {
        return messages.size();
    }
}

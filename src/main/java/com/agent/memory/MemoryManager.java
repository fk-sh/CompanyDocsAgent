package com.agent.memory;

import com.agent.core.AgentContext;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆模块统一入口，封装记忆体系的全部操作。
 * <p>
 * <b>职责</b>：
 * <ul>
 *   <li>会话生命周期管理：创建、查询、归档、删除</li>
 *   <li>消息持久化：将对话写入 ConversationMemory + MySQL（异步）</li>
 *   <li>上下文拼装：{@link #buildContext} 一次调用将记忆注入 AgentContext</li>
 *   <li>情景记忆：会话结束时将对话提炼为结构化情景摘要写入 ES</li>
 * </ul>
 * <p>
 * <b>记忆注入顺序</b>（{@link #buildContext} 内）：
 * <ol>
 *   <li>{@code episodicContext} — ES 语义检索的 Top-K 相关情景记忆</li>
 *   <li>{@code userId} — 从 session 解析的用户标识</li>
 *   <li>{@code history} — ConversationMemory 滑动窗口中的近期对话</li>
 *   <li>{@code memoryContext} — 格式化后的完整上下文 Prompt 文本</li>
 * </ol>
 *
 * @see ConversationMemory
 * @see EpisodicMemory
 */
@Slf4j
@Service
public class MemoryManager {

    private final ConversationMemory conversationMemory;// 对话记忆，滑动窗口存储近期对话
    private final EpisodicMemory episodicMemory;// 情景记忆，语义检索相关情景摘要
    private final MysqlSessionStore sessionStore;// 会话存储，MySQL 表
    private final MysqlMessageStore messageStore;// 消息存储，MySQL 表
    private final DeepSeekChatClient chatClient;// 模型客户端，用于调用 DeepSeek 模型

    public MemoryManager(ConversationMemory conversationMemory,
                         EpisodicMemory episodicMemory,
                         MysqlSessionStore sessionStore,
                         MysqlMessageStore messageStore,
                         DeepSeekChatClient chatClient) {
        this.conversationMemory = conversationMemory;
        this.episodicMemory = episodicMemory;
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.chatClient = chatClient;
    }

    // ======================== 会话生命周期 ========================

    /**
     * 创建新会话，自动生成 UUID 格式的 sessionId。
     * 同步写入 MySQL {@code agent_sessions} 表。
     *
     * @param userId 关联用户 ID
     * @return 新会话的 sessionId
     */
    public String createSession(String userId) {
        AgentSession session = sessionStore.create(userId, "新对话");
        log.info("Created session {} for user {}", session.getId(), userId);
        return session.getId();
    }

    /**
     * 创建新会话并指定标题。
     *
     * @param userId 关联用户 ID
     * @param title  会话标题
     * @return 新会话的 sessionId
     */
    public String createSession(String userId, String title) {
        AgentSession session = sessionStore.create(userId, title);
        log.info("Created session {} for user {}: {}", session.getId(), userId, title);
        return session.getId();
    }

    /**
     * 查询会话记录。
     *
     * @return {@code Optional.empty()} 表示会话不存在
     */
    public Optional<AgentSession> getSession(String sessionId) {
        return Optional.ofNullable(sessionStore.findById(sessionId));
    }

    /**
     * 分页查询用户的会话列表，按更新时间降序。
     */
    public List<AgentSession> getUserSessions(String userId, int limit, int offset) {
        return sessionStore.findByUserId(userId, limit, offset);
    }

    /**
     * 归档会话：先提取情景记忆，再更新 MySQL 状态为 ARCHIVED，最后清空短期记忆。
     */
    public void endSession(String sessionId) {
        endSessionWithEpisodicMemory(sessionId);
        sessionStore.updateStatus(sessionId, "ARCHIVED");
        conversationMemory.clear();
        log.info("Ended session {}", sessionId);
    }

    /**
     * 会话结束时提取情景记忆并异步写入 ES。
     * <p>
     * 将当前 ConversationMemory 中的完整对话（含历史摘要）
     * 送给 LLM 提炼出话题标签、关键实体、意图序列，
     * 作为一条情景摘要存入 ES agent_episodic_memory 索引。
     * 后续新会话中，通过语义检索召回相关情景记忆。
     */
    public void endSessionWithEpisodicMemory(String sessionId) {
        String conversation = conversationMemory.buildContextPrompt();
        if (conversation.isEmpty()) {
            return;
        }
        episodicMemory.extractAndStore(sessionId, conversation);
    }

    /**
     * 级联删除会话及其所有关联数据：
     * MySQL sessions → MySQL messages → ES 情景记忆 → 清空内存。
     */
    public void deleteSession(String sessionId) {
        sessionStore.delete(sessionId);
        messageStore.deleteBySessionId(sessionId);
        episodicMemory.deleteBySessionId(sessionId);
        conversationMemory.clear();
        log.info("Deleted session {}", sessionId);
    }

    /**
     * 根据 sessionId 查询关联的 userId。
     * sessionId 不存在或无关联用户时返回 "anonymous"。
     */
    public String resolveUserId(String sessionId) {
        AgentSession session = sessionStore.findById(sessionId);
        return session != null ? session.getUserId() : "anonymous";
    }

    /**
     * 更新会话标题，同步刷新 updated_at。
     */
    public void updateSessionTitle(String sessionId, String title) {
        sessionStore.updateTitle(sessionId, title);
    }

    /**
     * 获取指定会话的消息列表（按时间正序）。
     */
    public List<Message> getSessionMessages(String sessionId, int limit) {
        return messageStore.findBySessionId(sessionId, limit);
    }

    // ======================== 上下文构建 ========================

    /**
     * 构建完整的上下文对象，拼接记忆。
     * <p>
     * 一次调用完成：
     * <ol>
     *   <li>从 MySQL 加载该 session 的历史消息到 ConversationMemory</li>
     *   <li>用当前 query 在 ES 中语义检索 Top-5 相关情景记忆 → 写入 {@code ctx.episodicContext}</li>
     *   <li>从 session 解析 userId → 写入 {@code ctx.userId}</li>
     *   <li>将 ConversationMemory 中的近期消息写入 {@code ctx.history}</li>
     *   <li>调用 {@link ConversationMemory#buildContextPrompt()} 生成格式化文本 →
     *       写入 {@code ctx.memoryContext}</li>
     * </ol>
     *
     * @param sessionId 会话 ID
     * @param userQuery 用户当前问题
     * @return 注入完毕的 AgentContext，后续 Agent 直接从中读取记忆
     */
    public AgentContext buildContext(String sessionId, String userQuery) {
        AgentContext ctx = new AgentContext(sessionId, userQuery);

        loadSessionMemory(sessionId);

        AgentSession session = sessionStore.findById(sessionId);
        String userId = session != null ? session.getUserId() : "anonymous";
        ctx.setVariable("userId", userId);

        String episodicContext = episodicMemory.buildEpisodeContextText(userQuery, 5);
        if (!episodicContext.isEmpty()) {
            ctx.setVariable("episodicContext", episodicContext);
        }

        List<Message> recentMessages = conversationMemory.getAll();
        for (Message msg : recentMessages) {
            ctx.addMessage(msg);
        }

        String contextPrompt = conversationMemory.buildContextPrompt();
        if (!contextPrompt.isEmpty()) {
            ctx.setVariable("memoryContext", contextPrompt);
        }

        log.debug("Built context for session {}, historySize={}", sessionId, recentMessages.size());
        return ctx;
    }

    /**
     * 从 MySQL 加载历史消息到 ConversationMemory。
     * 先清空内存再加载，避免重复。
     */
    public void loadSessionMemory(String sessionId) {
        conversationMemory.clear();

        List<Message> dbMessages = messageStore.findBySessionId(sessionId, 50);
        if (!dbMessages.isEmpty()) {
            conversationMemory.addAll(dbMessages);
            log.debug("Loaded {} messages from DB for session {}", dbMessages.size(), sessionId);
        }
    }

    // ======================== 消息写入 ========================

    /**
     * 写入消息到两层存储：
     * ConversationMemory（内存） + MySQL（异步）。
     */
    public void addMessage(String sessionId, Message message) {
        conversationMemory.add(message);
        persistMessageAsync(sessionId, message);
    }

    /**
     * 构造并保存一条 USER 角色消息。
     */
    public void saveUserMessage(String sessionId, String content) {
        Message msg = Message.user(content);
        msg.setId(UUID.randomUUID().toString().replace("-", ""));
        addMessage(sessionId, msg);
    }

    /**
     * 构造并保存一条 ASSISTANT 角色消息。
     */
    public void saveAssistantMessage(String sessionId, String content) {
        Message msg = Message.assistant(content);
        msg.setId(UUID.randomUUID().toString().replace("-", ""));
        addMessage(sessionId, msg);
    }

    /**
     * 构造并保存一条 SYSTEM 角色消息。
     */
    public void saveSystemMessage(String sessionId, String content) {
        Message msg = Message.system(content);
        msg.setId(UUID.randomUUID().toString().replace("-", ""));
        addMessage(sessionId, msg);
    }

    // ======================== 查询 ========================

    /**
     * 获取最近 N 条消息（短期记忆窗口中的最新内容）。
     */
    public List<Message> getRecentMessages(int count) {
        return conversationMemory.getRecent(count);
    }

    /**
     * 获取当前短期记忆窗口中的全部消息。
     */
    public List<Message> getAllMessages() {
        return conversationMemory.getAll();
    }

    // ======================== 内部方法 ========================

    /**
     * 异步持久化单条消息到 MySQL。
     * 独立的 @Async 方法，不依赖 MysqlMessageStore 的异步方法，
     * 避免代理嵌套问题。
     */
    @Async
    public void persistMessageAsync(String sessionId, Message message) {
        try {
            messageStore.save(sessionId, message);// 异步写入 MySQL
        } catch (Exception e) {
            log.warn("Async persist message failed: {}", e.getMessage());
        }
    }
}

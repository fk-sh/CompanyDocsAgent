package com.agent.memory;

import com.agent.core.AgentContext;
import com.agent.core.Memory;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 记忆模块统一入口，封装三层记忆体系的全部操作。
 * <p>
 * <b>职责</b>：
 * <ul>
 *   <li>会话生命周期管理：创建、查询、归档、删除</li>
 *   <li>消息持久化：将对话写入 ConversationMemory + MySQL + ES VectorMemory（三层同步）</li>
 *   <li>上下文拼装：{@link #buildContext} 一次调用将记忆注入 AgentContext</li>
 *   <li>用户画像提取：{@link #extractUserProfile} 从对话中异步提取用户偏好</li>
 * </ul>
 * <p>
 * <b>三层记忆注入顺序</b>（{@link #buildContext} 内）：
 * <ol>
 *   <li>{@code relevantLongTermMemory} — ES 语义检索的 Top-5 相关记忆（跨会话精确匹配）</li>
 *   <li>{@code userProfile} — MySQL 用户画像（跨会话背景信息）</li>
 *   <li>{@code history} — ConversationMemory 滑动窗口中的近期对话（当前会话完整细节）</li>
 *   <li>{@code memoryContext} — 格式化后的完整上下文 Prompt 文本</li>
 * </ol>
 * <p>
 * <b>可选依赖处理</b>：{@link VectorMemory} 和 {@link UserMemory} 通过
 * {@code List<Memory>} + {@code instanceof} 方式注入，
 * ES/MySQL 不可用时自动降级，不影响核心功能。
 *
 * @see ConversationMemory
 * @see VectorMemory
 * @see UserMemory
 */
@Slf4j
@Service
public class MemoryManager {

    private final ConversationMemory conversationMemory;
    private final VectorMemory vectorMemory;
    private final UserMemory userMemory;
    private final MysqlSessionStore sessionStore;
    private final MysqlMessageStore messageStore;
    private final DeepSeekChatClient chatClient;

    /**
     * 通过 {@code List<Memory>} 注入所有 Memory 实现类，
     * 再通过 {@code instanceof} 分流到具体类型。
     * <p>
     * 用此方式而非直接按类型注入的原因是：
     * VectorMemory 有 @Async 方法，Spring 会为其创建 JDK 动态代理，
     * 代理只暴露接口类型（Memory），导致按具体类型注入失败。
     */
    public MemoryManager(ConversationMemory conversationMemory,
                         List<Memory> memoryBeans,
                         MysqlSessionStore sessionStore,
                         MysqlMessageStore messageStore,
                         DeepSeekChatClient chatClient) {
        this.conversationMemory = conversationMemory;
        this.sessionStore = sessionStore;
        this.messageStore = messageStore;
        this.chatClient = chatClient;

        VectorMemory vm = null;
        UserMemory um = null;
        for (Memory mem : memoryBeans) {
            if (mem instanceof VectorMemory v) {
                vm = v;
            } else if (mem instanceof UserMemory u) {
                um = u;
            }
        }
        this.vectorMemory = vm;
        this.userMemory = um;
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
     * 归档会话：更新 MySQL 状态为 ARCHIVED → 清空短期记忆。
     */
    public void endSession(String sessionId) {
        sessionStore.updateStatus(sessionId, "ARCHIVED");
        conversationMemory.clear();
        log.info("Ended session {}", sessionId);
    }

    /**
     * 级联删除会话及其所有关联数据：
     * MySQL sessions → MySQL messages → ES 长期记忆 → 清空内存。
     */
    public void deleteSession(String sessionId) {
        sessionStore.delete(sessionId);
        messageStore.deleteBySessionId(sessionId);
        if (vectorMemory != null) {
            vectorMemory.deleteBySessionId(sessionId);
        }
        conversationMemory.clear();
        log.info("Deleted session {}", sessionId);
    }

    /**
     * 更新会话标题，同步刷新 updated_at。
     */
    public void updateSessionTitle(String sessionId, String title) {
        sessionStore.updateTitle(sessionId, title);
    }

    // ======================== 上下文构建 ========================

    /**
     * 构建完整的上下文对象，拼接三层记忆。
     * <p>
     * 这是记忆模块最重要的入口方法，一次调用完成：
     * <ol>
     *   <li>从 MySQL 加载该 session 的历史消息到 ConversationMemory</li>
     *   <li>用当前 query 在 ES 中语义检索 Top-5 相关记忆 → 写入 {@code ctx.relevantLongTermMemory}</li>
     *   <li>根据 session 关联的 userId 读取用户画像 → 写入 {@code ctx.userProfile}</li>
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

        loadSessionMemory(sessionId);// 加载会话历史消息

        if (vectorMemory != null) {
            List<Message> relevantHistory = vectorMemory.search(userQuery, 5);// 从 ES 中语义检索 Top-5 相关记忆
            if (!relevantHistory.isEmpty()) {
                ctx.setVariable("relevantLongTermMemory", relevantHistory);// 写入相关记忆
            }
        }

        if (userMemory != null) {
            AgentSession session = sessionStore.findById(sessionId);// 查询会话记录
            if (session != null) {
                String userProfile = userMemory.buildUserContextPrompt(session.getUserId());// 构建用户画像提示词
                if (!userProfile.isEmpty()) {
                    ctx.setVariable("userProfile", userProfile);// 写入用户画像
                }
            }
        }

        List<Message> recentMessages = conversationMemory.getAll();
        for (Message msg : recentMessages) {
            ctx.addMessage(msg);
        }

        String contextPrompt = conversationMemory.buildContextPrompt();// 构建上下文提示词
        if (!contextPrompt.isEmpty()) {
            ctx.setVariable("memoryContext", contextPrompt);// 写入上下文提示词
        }

        log.debug("Built context for session {}, historySize={}", sessionId, recentMessages.size());
        return ctx;// 返回构建好的上下文
    }

    /**
     * 从 MySQL 加载历史消息到 ConversationMemory。
     * 先清空内存再加载，避免重复。
     */
    public void loadSessionMemory(String sessionId) {
        conversationMemory.clear();// 清空内存

        List<Message> dbMessages = messageStore.findBySessionId(sessionId, 50);// 查询会话历史消息
        // 50 条消息足够覆盖大多数会话，避免查询所有消息
        if (!dbMessages.isEmpty()) {
            conversationMemory.addAll(dbMessages);// 加载到 ConversationMemory
            log.debug("Loaded {} messages from DB for session {}", dbMessages.size(), sessionId);
        }

        if (vectorMemory != null) {
            vectorMemory.setCurrentSessionId(sessionId);
        }
    }

    // ======================== 消息写入 ========================

    /**
     * 写入消息到三层存储：
     * ConversationMemory（内存） + MySQL（异步） + ES VectorMemory（异步）。
     */
    public void addMessage(String sessionId, Message message) {
        conversationMemory.add(message);// 写入 ConversationMemory
        persistMessageAsync(sessionId, message);// 异步写入 MySQL
        if (vectorMemory != null) {
            vectorMemory.addAsync(message);// 异步写入 ES VectorMemory
        }
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

    // ======================== 用户画像 ========================

    /**
     * 从用户消息中提取偏好，异步存入用户画像。
     * <p>
     * 从会话中读取 userId，将 content 送给 {@link UserMemory#extractAndSaveAsync}
     * 做 LLM 提取 + 增量合并，结果写入 MySQL {@code user_profiles} 表。
     *
     * @param sessionId          会话 ID（用于查找 userId）
     * @param userMessageContent 用户消息正文（仅 USER 角色消息）
     */
    public void extractUserProfile(String sessionId, String userMessageContent) {
        if (userMemory == null) {
            return;
        }
        AgentSession session = sessionStore.findById(sessionId);
        if (session != null && session.getUserId() != null && !session.getUserId().isBlank()) {
            userMemory.extractAndSaveAsync(session.getUserId(), userMessageContent);
        }
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

package com.agent.memory;

import com.agent.core.Message;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息持久化仓库，通过 MyBatis-Plus 操作 MySQL {@code agent_messages} 表。
 * <p>
 * <b>核心设计</b>：
 * <ul>
 *   <li><b>异步优先</b>：{@link #saveAsync} / {@link #saveBatchAsync} 标记 {@code @Async}，
 *       写入不阻塞 Agent 主流程。同步版 {@link #save} / {@link #saveBatch} 供测试和回滚场景使用</li>
 *   <li><b>PO ↔ Message 双向转换</b>：
 *       {@link #toPo} 将业务对象转为数据库行（写），
 *       {@link #toMessages} 将数据库行转为业务对象（读）</li>
 *   <li><b>Token 估算</b>：写入时自动估算每条消息的 Token 数并存入 {@code token_count} 列。
 *       公式：{@code ceil(字符数 / 3.5)}，中文场景 1 Token ≈ 3~4 个字符</li>
 *   <li><b>时间转换</b>：{@link Message#getTimestamp()} 是 {@link Instant}，
 *       MySQL 存储为 {@link LocalDateTime}（系统默认时区）</li>
 * </ul>
 * <p>
 * 查询全部使用 {@link LambdaQueryWrapper} 构建类型安全的条件，无需手写 SQL。
 *
 * @see AgentMessagePO
 * @see AgentMessageMapper
 */
@Slf4j
@Repository
public class MysqlMessageStore {

    /** 1 Token ≈ 3.5 个中文字符 */
    private static final double TOKENS_PER_CHAR = 3.5;

    private final AgentMessageMapper mapper;

    public MysqlMessageStore(AgentMessageMapper mapper) {
        this.mapper = mapper;
    }

    // ======================== 异步写入（生产环境主路径） ========================

    /**
     * 异步写入单条消息，Spring 异步线程池执行，不阻塞调用线程。
     */
    @Async
    public void saveAsync(String sessionId, Message message) {
        save(sessionId, message);
    }

    /**
     * 异步批量写入消息。每条消息独立 insert，适合消息量不大的场景。
     */
    @Async
    public void saveBatchAsync(String sessionId, List<Message> messages) {
        saveBatch(sessionId, messages);
    }

    // ======================== 同步写入（测试 / 兜底） ========================

    /**
     * 同步写入单条消息。id 由 MyBatis-Plus 自动生成。
     */
    public void save(String sessionId, Message message) {
        AgentMessagePO po = toPo(sessionId, message);
        mapper.insert(po);
        log.debug("Saved message {} to session {}", po.getId(), sessionId);
    }

    /**
     * 同步批量写入。每条独立 insert，未使用真正的 batch 语句
     * （MyBatis-Plus 需额外配置才能开启 JDBC batch），
     * 但相比逐条调用减少了方法调用开销。
     */
    public void saveBatch(String sessionId, List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<AgentMessagePO> poList = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            poList.add(toPo(sessionId, msg));
        }
        for (AgentMessagePO po : poList) {
            mapper.insert(po);
        }
        log.debug("Batch saved {} messages to session {}", messages.size(), sessionId);
    }

    // ======================== 查询 ========================

    /**
     * 按时间正序查询会话消息（旧→新），用于还原对话时序。
     * <p>
     * 查询条件：session_id = ?，按 created_at ASC，限制 limit 条。
     */
    public List<Message> findBySessionId(String sessionId, int limit) {
        LambdaQueryWrapper<AgentMessagePO> qw = new LambdaQueryWrapper<>();
        qw.eq(AgentMessagePO::getSessionId, sessionId)
                .orderByAsc(AgentMessagePO::getCreatedAt)
                .last("LIMIT " + limit);
        return toMessages(mapper.selectList(qw));
    }

    /**
     * 按时间倒序查询会话消息（新→旧），用于取最近的 N 条。
     */
    public List<Message> findRecentBySessionId(String sessionId, int limit) {
        LambdaQueryWrapper<AgentMessagePO> qw = new LambdaQueryWrapper<>();
        qw.eq(AgentMessagePO::getSessionId, sessionId)
                .orderByDesc(AgentMessagePO::getCreatedAt)
                .last("LIMIT " + limit);
        return toMessages(mapper.selectList(qw));
    }

    /**
     * 统计某会话的消息总数。
     * {@code selectCount} 返回 {@code Long}，转为 {@code int}。
     */
    public int countBySessionId(String sessionId) {
        LambdaQueryWrapper<AgentMessagePO> qw = new LambdaQueryWrapper<>();
        qw.eq(AgentMessagePO::getSessionId, sessionId);
        return mapper.selectCount(qw).intValue();
    }

    // ======================== 删除 ========================

    /**
     * 删除某会话下的所有消息（会话删除时级联调用）。
     */
    public void deleteBySessionId(String sessionId) {
        LambdaQueryWrapper<AgentMessagePO> qw = new LambdaQueryWrapper<>();
        qw.eq(AgentMessagePO::getSessionId, sessionId);
        mapper.delete(qw);
        log.debug("Deleted all messages for session {}", sessionId);
    }

    // ======================== 内部转换方法 ========================

    /**
     * 业务对象 → 持久化对象。
     * 同时完成时间类型转换（Instant → LocalDateTime）和 Token 估算。
     */
    private AgentMessagePO toPo(String sessionId, Message message) {
        AgentMessagePO po = new AgentMessagePO();
        po.setSessionId(sessionId);
        po.setRole(message.getRole().name());
        po.setContent(message.getContent());
        po.setToolCallId(message.getToolCallId());
        po.setTokenCount(estimateTokenCount(message.getContent()));
        po.setCreatedAt(toLocalDateTime(message.getTimestamp()));
        return po;
    }

    /**
     * 持久化对象列表 → 业务对象列表。
     * 逐条转换并在最终列表完成前做一次完整拷贝。
     */
    private List<Message> toMessages(List<AgentMessagePO> poList) {
        List<Message> messages = new ArrayList<>(poList.size());
        for (AgentMessagePO po : poList) {
            Message msg = new Message();
            msg.setId(po.getId());
            msg.setRole(Message.Role.valueOf(po.getRole()));
            msg.setContent(po.getContent());
            msg.setToolCallId(po.getToolCallId());
            msg.setTimestamp(toInstant(po.getCreatedAt()));
            messages.add(msg);
        }
        return messages;
    }

    /**
     * 估算文本 Token 数。中文场景 1 Token ≈ 3.5 个字符。
     */
    private int estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / TOKENS_PER_CHAR);
    }

    /**
     * Instant → LocalDateTime（系统默认时区 Asia/Shanghai）。
     */
    private static LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    /**
     * LocalDateTime → Instant（系统默认时区 Asia/Shanghai）。
     */
    private static Instant toInstant(LocalDateTime ldt) {
        if (ldt == null) {
            return Instant.now();
        }
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
    }
}

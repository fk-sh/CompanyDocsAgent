package com.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话持久化仓库，通过 MyBatis-Plus 操作 MySQL {@code agent_sessions} 表。
 * <p>
 * 全部 SQL 由 MyBatis-Plus 自动生成，无需手写。
 * 使用 {@link LambdaQueryWrapper} / {@link LambdaUpdateWrapper} 构建类型安全的查询条件，
 * 避免字符串拼接 SQL，编译期即可发现字段名变更错误。
 * <p>
 * <b>主键策略</b>：{@code @TableId(type = IdType.ASSIGN_UUID)}，
 * MyBatis-Plus 在 insert 时自动生成 UUID，不依赖数据库自增。
 * <p>
 * <b>Lambda 链式 API 示例</b>：
 * <pre>{@code
 *   // 等价于: SELECT * FROM agent_sessions WHERE user_id = ? ORDER BY updated_at DESC LIMIT 20 OFFSET 0
 *   LambdaQueryWrapper<AgentSession> qw = new LambdaQueryWrapper<>();
 *   qw.eq(AgentSession::getUserId, userId)
 *      .orderByDesc(AgentSession::getUpdatedAt)
 *      .last("LIMIT 20 OFFSET 0");
 *   mapper.selectList(qw);
 * }</pre>
 *
 * @see AgentSession
 * @see AgentSessionMapper
 */
@Slf4j
@Repository
public class MysqlSessionStore {

    private final AgentSessionMapper mapper;

    public MysqlSessionStore(AgentSessionMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 创建新会话。id 由 MyBatis-Plus 自动生成，status 初始为 ACTIVE。
     *
     * @param userId 关联用户 ID
     * @param title  会话标题
     * @return 保存后的会话实体（含自动生成的 id）
     */
    public AgentSession create(String userId, String title) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setTitle(title);
        session.setStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        mapper.insert(session);
        log.debug("Created session {}", session.getId());
        return session;
    }

    /**
     * 按主键查询。与旧 JdbcTemplate 版不同，不存在时返回 {@code null} 而非 Optional。
     */
    public AgentSession findById(String sessionId) {
        return mapper.selectById(sessionId);
    }

    /**
     * 按 userId 分页查询会话列表，按更新时间降序（最新修改的排前面）。
     * <p>
     * {@code .last("LIMIT n OFFSET m")} 是 MyBatis-Plus 的兜底拼接，
     * 用于不支持标准分页写法时的直接 SQL 片段拼接。
     *
     * @param userId 用户 ID
     * @param limit  每页条数
     * @param offset 偏移量
     */
    public List<AgentSession> findByUserId(String userId, int limit, int offset) {
        LambdaQueryWrapper<AgentSession> qw = new LambdaQueryWrapper<>();
        qw.eq(AgentSession::getUserId, userId)
                .orderByDesc(AgentSession::getUpdatedAt)
                .last("LIMIT " + limit + " OFFSET " + offset);
        return mapper.selectList(qw);
    }

    /**
     * 更新会话状态（ACTIVE → ARCHIVED），同时刷新 updated_at。
     * <p>
     * {@link LambdaUpdateWrapper} 只更新指定的列，比全量 updateById 更高效。
     */
    public void updateStatus(String sessionId, String status) {
        LambdaUpdateWrapper<AgentSession> uw = new LambdaUpdateWrapper<>();
        uw.eq(AgentSession::getId, sessionId)
                .set(AgentSession::getStatus, status)
                .set(AgentSession::getUpdatedAt, LocalDateTime.now());
        mapper.update(uw);
        log.debug("Updated session {} status to {}", sessionId, status);
    }

    /**
     * 更新会话标题，同时刷新 updated_at。
     */
    public void updateTitle(String sessionId, String title) {
        LambdaUpdateWrapper<AgentSession> uw = new LambdaUpdateWrapper<>();
        uw.eq(AgentSession::getId, sessionId)
                .set(AgentSession::getTitle, title)
                .set(AgentSession::getUpdatedAt, LocalDateTime.now());
        mapper.update(uw);
    }

    /**
     * 按主键物理删除。不级联，调用方需先清理关联数据。
     */
    public void delete(String sessionId) {
        mapper.deleteById(sessionId);
        log.debug("Deleted session {}", sessionId);
    }
}

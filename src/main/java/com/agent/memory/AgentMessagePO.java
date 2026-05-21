package com.agent.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息持久化对象（PO），对应 MySQL {@code agent_messages} 表。
 * <p>
 * 这是纯粹的数据库映射实体，与业务层的 {@link com.agent.core.Message} 分离：
 * <ul>
 *   <li>{@link com.agent.core.Message} — 业务领域对象，带 {@code Instant} 时间戳、静态工厂方法</li>
 *   <li>{@code AgentMessagePO} — 数据库持久化对象，带 {@code LocalDateTime} 时间字段、MyBatis-Plus 映射</li>
 * </ul>
 * 两者通过 {@link MysqlMessageStore#toPo} / {@link MysqlMessageStore#toMessages} 双向转换。
 * <p>
 * 主键同样使用 {@link IdType#ASSIGN_UUID}。
 *
 * @see AgentMessageMapper
 * @see MysqlMessageStore
 */
@Data
@TableName("agent_messages")
public class AgentMessagePO {

    /** 消息唯一标识，MyBatis-Plus 自动生成 UUID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属会话 ID */
    @TableField("session_id")
    private String sessionId;

    /** 消息角色：SYSTEM / USER / ASSISTANT / TOOL */
    private String role;

    /** 消息正文，最长 16MB（MEDIUMTEXT） */
    private String content;

    /** 工具调用 ID，仅 TOOL 角色时有值 */
    @TableField("tool_call_id")
    private String toolCallId;

    /** 预估 Token 数，公式：ceil(字符数 / 3.5) */
    @TableField("token_count")
    private Integer tokenCount;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}

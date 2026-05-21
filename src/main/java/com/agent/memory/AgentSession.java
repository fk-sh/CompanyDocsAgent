package com.agent.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体类，对应 MySQL {@code agent_sessions} 表。
 * <p>
 * MyBatis-Plus 通过此 POJO 自动生成全部 CRUD SQL，无需手写。
 * 主键使用 {@link IdType#ASSIGN_UUID}，由 Java 端生成 UUID（去掉横线），
 * 不依赖数据库自增，方便后续分布式扩展。
 * <p>
 * 下划线字段（如 {@code user_id}）通过 {@link TableField} 显式映射，
 * 其余字段利用 MyBatis-Plus 默认的驼峰→下划线自动转换。
 *
 * @see AgentSessionMapper
 * @see MysqlSessionStore
 */
@Data
@TableName("agent_sessions")
public class AgentSession {

    /** 会话唯一标识，MyBatis-Plus 自动生成 UUID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 关联用户 ID，支持多租户 */
    @TableField("user_id")
    private String userId;

    /** 会话标题，默认取首条用户问题前 20 字 */
    private String title;

    /** ACTIVE（活跃）/ ARCHIVED（归档） */
    private String status;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间（每次修改自动刷新） */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

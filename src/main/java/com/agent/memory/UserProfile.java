package com.agent.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户画像实体，对应 MySQL {@code user_profiles} 表。
 * <p>
 * 存储由 LLM 从对话中提取的跨会话用户偏好信息，如城市、语言、兴趣等。
 * 每个用户仅一条记录（{@code user_id} 唯一索引），通过增量合并更新。
 * <p>
 * {@code preferences} 为 JSON 字符串，由 {@link UserMemory#buildUserContextPrompt}
 * 解析为自然语言后注入 AgentContext。
 *
 * @see UserProfileMapper
 * @see UserMemory
 */
@Data
@TableName("user_profiles")
public class UserProfile {

    /** 主键，MyBatis-Plus 自动生成 UUID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 用户 ID，唯一索引，每个用户仅一条画像记录 */
    @TableField("user_id")
    private String userId;

    /**
     * 用户偏好 JSON，如：
     * <pre>{@code {"city":"北京","role":"程序员","interests":["篮球"]}}</pre>
     */
    private String preferences;

    /** 原始提取记录，供 LLM 增量合并时参考（预留字段） */
    @TableField("raw_notes")
    private String rawNotes;

    /** 版本号，每次画像更新 +1 */
    private Integer version;

    /** 首次创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 最后更新时间（每次画像更新后刷新） */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}

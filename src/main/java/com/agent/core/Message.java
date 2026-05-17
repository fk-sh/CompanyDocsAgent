package com.agent.core;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 消息模型，表示对话中的一条消息。
 * <p>
 * 支持四种角色：
 * <ul>
 *   <li><b>SYSTEM</b>：系统指令，设定 Agent 行为边界</li>
 *   <li><b>USER</b>：用户输入</li>
 *   <li><b>ASSISTANT</b>：Agent/LLM 回复</li>
 *   <li><b>TOOL</b>：工具调用结果返回值</li>
 * </ul>
 * 提供便捷的静态工厂方法快速构造不同角色的消息。
 */
@Data
@NoArgsConstructor
public class Message {

    /** 消息角色枚举 */
    public enum Role {
        /** 系统提示词 */
        SYSTEM,
        /** 用户消息 */
        USER,
        /** 助手/AI 回复 */
        ASSISTANT,
        /** 工具调用返回 */
        TOOL
    }

    /** 消息唯一标识 */
    private String id;

    /** 消息角色 */
    private Role role;

    /** 消息正文内容 */
    private String content;

    /** 消息时间戳 */
    private Instant timestamp;

    /** TOOL 角色时关联的工具调用 ID */
    private String toolCallId;

    public Message(Role role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = Instant.now();
    }

    /** 构造一条系统消息 */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    /** 构造一条用户消息 */
    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    /** 构造一条助手消息 */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content);
    }

    /** 构造一条工具返回消息 */
    public static Message tool(String toolCallId, String content) {
        Message msg = new Message(Role.TOOL, content);
        msg.toolCallId = toolCallId;
        return msg;
    }
}

package com.agent.core;

import java.util.List;

/**
 * 记忆接口，支撑多轮对话的三层记忆体系。
 * <p>
 * 三层模型：
 * <ul>
 *   <li><b>Working Memory</b>：单次 execute() 内的中间结果，生命周期最短</li>
 *   <li><b>Short-Term Memory</b>：当前会话的多轮对话历史，生命周期为一个 Session</li>
 *   <li><b>Long-Term Memory</b>：跨会话的用户画像、历史摘要、关键知识，持久化存储</li>
 * </ul>
 * <p>
 * 自动压缩：当 Token 超限时，旧对话被压缩成摘要释放空间。
 * 异步持久化：写入通过 Spring {@code @Async} 异步落库，不阻塞 Agent 主流程。
 */
public interface Memory {

    /** 记忆类型枚举 */
    enum MemoryType {
        /** 工作记忆：单次推理中的中间结果 */
        WORKING,
        /** 短期记忆：当前会话的多轮对话 */
        SHORT_TERM,
        /** 长期记忆：跨会话持久化 */
        LONG_TERM
    }

    /**
     * @return 当前记忆实例的类型
     */
    MemoryType type();

    /**
     * 追加一条消息到记忆中。
     */
    void add(Message message);

    /**
     * 批量追加消息。
     */
    void addAll(List<Message> messages);

    /**
     * 获取最近 N 条消息。
     *
     * @param count 条数
     * @return 最近的 count 条消息
     */
    List<Message> getRecent(int count);

    /**
     * @return 记忆中的所有消息
     */
    List<Message> getAll();

    /**
     * Token 超限时自动压缩旧对话为摘要。
     *
     * @param maxTokens 最大 Token 数阈值
     */
    void compact(int maxTokens);

    /**
     * 清空记忆。
     */
    void clear();
}

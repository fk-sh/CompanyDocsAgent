package com.agent.core;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 执行上下文，在一次请求生命周期中贯穿所有 Agent。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>携带会话标识（sessionId）和用户输入（userQuery）</li>
 *   <li>通过 {@code variables}（ConcurrentHashMap）作为 Agent 间消息总线，解耦各 Agent</li>
 *   <li>维护对话历史 {@code history}，支持多轮对话</li>
 * </ul>
 * <p>
 * 典型用法：
 * <pre>{@code
 *   AgentContext ctx = new AgentContext(sessionId, userQuery);
 *   routerAgent.execute(ctx);
 *   // ctx.getVariable("intent") → "knowledge_qa"
 *   orchestratorAgent.execute(ctx);
 *   // ctx.getVariable("finalAnswer") → 最终答案
 * }</pre>
 */
@Getter
@Setter
public class AgentContext {

    /** 会话唯一标识 */
    private String sessionId;

    /** 用户当前输入的问题或指令 */
    private String userQuery;

    /**
     * Agent 间共享变量，作为消息总线解耦各 Agent。
     * 使用 ConcurrentHashMap 保证多 Agent 并发安全。
     */
    private final Map<String, Object> variables = new ConcurrentHashMap<>();

    /** 当前会话的对话历史 */
    private final List<Message> history = new ArrayList<>();

    public AgentContext() {
    }

    public AgentContext(String sessionId, String userQuery) {
        this.sessionId = sessionId;
        this.userQuery = userQuery;
    }

    /**
     * 向消息总线写入变量，供下游 Agent 读取。
     *
     * @param key   变量名
     * @param value 变量值
     */
    public void setVariable(String key, Object value) {
        variables.put(key, value);
    }

    /**
     * 从消息总线读取变量。
     *
     * @param key 变量名
     * @param <T> 期望的返回值类型
     * @return 变量值，可能为 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getVariable(String key) {
        return (T) variables.get(key);
    }

    /**
     * 从消息总线读取变量，不存在时返回默认值。
     *
     * @param key          变量名
     * @param defaultValue 默认值
     * @param <T>          返回值类型
     * @return 变量值或默认值
     */
    public <T> T getVariable(String key, T defaultValue) {
        T value = getVariable(key);
        return value != null ? value : defaultValue;
    }

    /**
     * @return true 表示消息总线中存在该变量
     */
    public boolean hasVariable(String key) {
        return variables.containsKey(key);
    }

    /**
     * 从消息总线移除变量。
     */
    public void removeVariable(String key) {
        variables.remove(key);
    }

    /**
     * @return 最近 20 条对话历史（由 Lombok 生成）
     */
    public List<Message> getHistory() {
        return history;
    }

    /** 向对话历史追加一条消息。 */
    public void addMessage(Message message) {
        history.add(message);
    }

    /** 清空对话历史。 */
    public void clearHistory() {
        history.clear();
    }
}

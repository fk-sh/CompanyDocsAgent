package com.agent.service;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.memory.MemoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 多 Agent 问答统一入口服务。
 * <p>
 * 对外的唯一入口，只依赖 {@link Agent} 接口，
 * 调用 {@code orchestratorAgent.execute(context)}，不感知下游有几个 Agent。
 * 替换 Phase 3 的 {@link SimpleChatService}。
 * <p>
 * 调用流程：
 * <ol>
 *   <li>通过 {@link MemoryManager} 构建带历史记忆的 AgentContext</li>
 *   <li>调用 OrchestratorAgent → RouterAgent 意图识别 → 分支调度</li>
 *   <li>从 context 中取 finalAnswer 返回</li>
 *   <li>保存本轮对话到 MemoryManager</li>
 * </ol>
 */
@Slf4j
@Service
public class ChatService {

    private final Agent orchestratorAgent;
    private final MemoryManager memoryManager;

    public ChatService(@Qualifier("orchestratorAgent") Agent orchestratorAgent, MemoryManager memoryManager) {
        this.orchestratorAgent = orchestratorAgent;
        this.memoryManager = memoryManager;
    }

    /**
     * 同步对话。
     *
     * @param sessionId  会话 ID
     * @param userQuery  用户问题
     * @return 最终答案
     */
    public String chat(String sessionId, String userQuery) {
        log.info("ChatService.chat session={}, query={}", sessionId, userQuery);

        AgentContext ctx = memoryManager.buildContext(sessionId, userQuery);// 构建带历史记忆的 AgentContext
        orchestratorAgent.execute(ctx);

        String finalAnswer = ctx.getVariable("finalAnswer", "抱歉，无法处理您的问题。");

        memoryManager.saveUserMessage(sessionId, userQuery);
        memoryManager.saveAssistantMessage(sessionId, finalAnswer);

        log.info("ChatService.chat completed, answer length={}", finalAnswer.length());
        return finalAnswer;
    }

    /**
     * 无会话的简单对话（临时会话，不保存历史）。
     *
     * @param userQuery 用户问题
     * @return 最终答案
     */
    public String chatOnce(String userQuery) {
        log.info("ChatService.chatOnce query={}", userQuery);

        AgentContext ctx = new AgentContext("temp-" + System.currentTimeMillis(), userQuery);
        orchestratorAgent.execute(ctx);

        return ctx.getVariable("finalAnswer", "抱歉，无法处理您的问题。");
    }
}

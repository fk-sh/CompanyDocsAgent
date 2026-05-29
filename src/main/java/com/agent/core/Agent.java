package com.agent.core;

import reactor.core.publisher.Flux;

/**
 * Agent 核心接口，系统中所有 Agent 的统一契约。
 * <p>
 * 每个 Agent 通过 {@link #execute(AgentContext)} 接收上下文并返回执行结果，
 * Agent 之间通过 {@link AgentContext#getVariables()} 传递数据，互不感知彼此实现。
 * <p>
 * 通过 {@link #skill()} 声明自身能力边界——做什么、输入什么、输出什么，
 * 这使得 Orchestrator 和 LLM 可以按技能匹配 Agent，而非硬编码路由。
 * <p>
 * 支持三种执行模式（在实现类中体现）：
 * <ul>
 *   <li><b>ReAct</b>：Think → Act → Observe 循环（在 OrchestratorAgent 中实现）</li>
 *   <li><b>Plan and Execute</b>：宏观调度，LLM 自主决策调用工具</li>
 *   <li><b>Reflection</b>：生成 → 审阅批评 → 重写</li>
 * </ul>
 *
 * @see AgentContext
 * @see AgentSkill
 */
public interface Agent {

    /**
     * @return Agent 的唯一名称标识，用于注册和调度
     */
    String name();

    /**
     * @return Agent 的技能声明，描述能力边界、输入依赖和输出产物。
     * 默认返回 null，表示该 Agent 尚未声明技能。
     * 实现此方法后，Orchestrator 和 LLM 可据此自动匹配和调度 Agent。
     */
    default AgentSkill skill() {
        return null;
    }

    /**
     * 同步执行 Agent 逻辑，读取上下文中的变量，执行完毕后将结果写回上下文。
     *
     * @param context 执行上下文，包含 sessionId、userQuery、variables、history
     * @return 执行结果摘要字符串
     */
    String execute(AgentContext context);

    /**
     * 流式执行 Agent 逻辑，默认实现将同步结果包装为单元素 Flux。
     * 支持 SSE 的 Agent 应重写此方法实现真正的流式输出。
     *
     * @param context 执行上下文
     * @return 流式结果序列
     */
    default Flux<String> executeStream(AgentContext context) {
        return Flux.just(execute(context));
    }
}

package com.agent.core;

import java.util.List;

/**
 * 编排器接口，继承 Agent，本身也是一个 Agent。
 * <p>
 * 核心职责：管理子 Agent 的注册和调度，按照意图（intent）分支路由到不同子 Agent 链路。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>继承 {@link Agent}：外部通过统一的 {@code agent.execute(context)} 调用</li>
 *   <li>子 Agent 注册为 Spring {@code @Component}，通过 {@code @Autowired List<Agent>} 自动发现</li>
 *   <li>新增 Agent：实现 Agent 接口 → 注册 Spring Bean → 自动被发现和调度</li>
 * </ul>
 * <p>
 * 三条路由链路：
 * <pre>
 *   intent="knowledge_qa"  → Retriever → Generator ⇄ Reviewer (Reflection)
 *   intent="chitchat"      → Generator（直接对话）
 *   intent="doc_ingestion" → IngestionAgent（离线管道）
 * </pre>
 */
public interface Orchestrator extends Agent {

    /**
     * 注册一个子 Agent 到编排器中。
     */
    void registerAgent(Agent agent);

    /**
     * 按名称移除子 Agent。
     */
    void removeAgent(String name);

    /**
     * @return 当前注册的所有子 Agent
     */
    List<Agent> getAgents();
}

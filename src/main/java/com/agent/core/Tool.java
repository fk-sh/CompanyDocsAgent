package com.agent.core;

import java.util.Map;

/**
 * 工具接口，Agent 可调用的外部能力抽象。
 * <p>
 * 在 ReAct 模式下，LLM 通过 {@link #description()} 了解工具用途并自主决定是否调用，
 * 调用参数通过 {@link #execute(Map)} 传入，结果返回给 LLM 继续推理。
 * <p>
 * 典型工具：文档搜索、精排、计算器等。
 */
public interface Tool {

    /**
     * @return 工具唯一名称
     */
    String name();

    /**
     * @return 给 LLM 看的工具描述，用于 Agent 自行判断是否调用
     */
    String description();

    /**
     * 执行工具逻辑。
     *
     * @param params 调用参数，由 LLM 决策生成
     * @return 工具执行结果
     */
    String execute(Map<String, Object> params);
}

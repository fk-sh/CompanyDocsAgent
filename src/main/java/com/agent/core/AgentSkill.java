package com.agent.core;

import java.util.List;

/**
 * Agent 技能声明，描述一个 Agent 的能力边界——做什么、输入什么、输出什么。
 * <p>
 * 类比 MCP 的 {@code McpTool}：正如 MCP 工具通过 name/description/inputSchema 让 LLM 理解
 * "有哪些工具可用、各需要什么参数"，Agent 通过 skill() 让编排器和 LLM 理解
 * "有哪些 Agent 可用、各擅长什么任务、依赖什么数据"。
 * <p>
 * 这使得：
 * <ul>
 *   <li>Orchestrator 可以按 skill 匹配 Agent，而非硬编码 switch-case</li>
 *   <li>LLM 可以在推理时自主选择调用哪个 Agent</li>
 *   <li>新 Agent 接入时只需声明 skill，无需修改 Orchestrator</li>
 * </ul>
 *
 * @param name        Agent 唯一名称
 * @param description Agent 能力描述，LLM 据此判断何时调用
 * @param inputs      需要的输入变量列表（从 AgentContext.variables 读取）
 * @param outputs     产生的输出变量列表（写入 AgentContext.variables）
 */
public record AgentSkill(
        String name,
        String description,
        List<VariableDef> inputs,
        List<VariableDef> outputs
) {

    /**
     * 变量定义，描述一个输入或输出变量。
     *
     * @param key         变量名（对应 AgentContext.variables 的 key）
     * @param type        变量类型
     * @param description 变量说明
     * @param required    是否必须
     */
    public record VariableDef(String key, String type, String description, boolean required) {
        public static VariableDef input(String key, String type, String description) {
            return new VariableDef(key, type, description, true);
        }

        public static VariableDef optionalInput(String key, String type, String description) {
            return new VariableDef(key, type, description, false);
        }

        public static VariableDef output(String key, String type, String description) {
            return new VariableDef(key, type, description, false);
        }
    }

    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Agent: ").append(name).append("\n");
        sb.append("能力: ").append(description).append("\n");
        sb.append("需要的输入:\n");
        for (VariableDef in : inputs) {
            String req = in.required() ? "必填" : "可选";
            sb.append("  - ").append(in.key()).append(" (").append(req)
                    .append(", ").append(in.type()).append("): ")
                    .append(in.description()).append("\n");
        }
        sb.append("产生的输出:\n");
        for (VariableDef out : outputs) {
            sb.append("  - ").append(out.key()).append(" (").append(out.type())
                    .append("): ").append(out.description()).append("\n");
        }
        return sb.toString();
    }
}

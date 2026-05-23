package com.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * MCP (Model Context Protocol) 工具接口。
 * <p>
 * 遵循 Anthropic MCP 规范，每个工具暴露 name/description/inputSchema，
 * LLM 通过 tools/list 发现工具，通过 tools/call 调用工具。
 * <p>
 * 相比 {@link com.agent.core.Tool}，MCP 工具额外提供 JSON Schema 格式的
 * 输入参数定义，使 LLM 能精确理解参数类型、必填项和约束。
 */
public interface McpTool {

    /**
     * @return 工具唯一名称，LLM 通过此名称发起 tools/call 请求
     */
    String name();

    /**
     * @return 工具描述，LLM 根据描述判断何时调用此工具
     */
    String description();

    /**
     * @return JSON Schema 格式的输入参数定义
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "city": { "type": "string", "description": "城市名" }
     *   },
     *   "required": ["city"]
     * }
     * }</pre>
     */
    String inputSchema();

    /**
     * 执行工具逻辑。
     *
     * @param arguments LLM 传入的参数（JSON 格式）
     * @return 工具执行结果（MCP 协议要求的文本内容）
     */
    McpToolResult call(Map<String, Object> arguments);
}

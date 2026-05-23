package com.agent.mcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具注册中心，管理所有 MCP 工具的生命周期。
 * <p>
 * LLM 通过 MCP 协议发现和调用工具：
 * <ol>
 *   <li>tools/list → 获取所有已注册工具的 name/description/inputSchema</li>
 *   <li>tools/call → 按名称调用对应工具，传入参数执行</li>
 * </ol>
 */
public class McpToolRegistry {

    private final Map<String, McpTool> tools = new ConcurrentHashMap<>();

    public void register(McpTool tool) {
        tools.put(tool.name(), tool);
    }

    public Optional<McpTool> get(String name) {
        return Optional.ofNullable(tools.get(name));// 根据名称获取工具
    }

    public List<McpTool> listTools() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));// 返回所有已注册工具
    }

    public McpToolResult call(String name, Map<String, Object> arguments) {
        McpTool tool = tools.get(name);// 根据名称获取工具
        if (tool == null) {
            return McpToolResult.error("Tool not found: " + name);// 工具不存在
        }
        try {
            return tool.call(arguments);// 执行工具逻辑
        } catch (Exception e) {
            return McpToolResult.error("Tool execution failed: " + e.getMessage());// 执行失败
        }
    }

    public void clear() {
        tools.clear();
    }

    public int size() {
        return tools.size();// 返回已注册工具数量
    }
}

package com.agent.mcp;

import java.util.List;

/**
 * MCP 工具调用结果，遵循 MCP 协议的 CallToolResult 结构。
 *
 * @param content 返回内容列表，每项含 type 和 text 字段
 * @param isError 是否执行出错
 */
public record McpToolResult(List<TextContent> content, boolean isError) {

    public McpToolResult {
        if (content == null) {
            content = List.of();
        }
    }

    public static McpToolResult success(String text) {
        return new McpToolResult(List.of(new TextContent("text", text)), false);
    }

    public static McpToolResult error(String errorMessage) {
        return new McpToolResult(List.of(new TextContent("text", errorMessage)), true);
    }

    /**
     * MCP 协议的文本内容块。
     *
     * @param type 固定为 "text"
     * @param text 文本内容
     */
    public record TextContent(String type, String text) {
    }
}

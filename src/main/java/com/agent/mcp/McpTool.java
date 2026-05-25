package com.agent.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;
import java.util.Map;

public interface McpTool {

    String name();

    String description();

    String inputSchema();

    CallToolResult call(Map<String, Object> arguments);

    default CallToolResult success(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .build();
    }

    default CallToolResult error(String errorMessage) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(errorMessage)))
                .isError(true)
                .build();
    }
}

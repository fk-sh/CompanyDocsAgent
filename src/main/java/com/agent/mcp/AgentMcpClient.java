package com.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AgentMcpClient {

    private String serverBaseUrl = "http://localhost:8080";

    private final McpJsonMapper jsonMapper;

    public AgentMcpClient(McpJsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void setServerBaseUrl(String serverBaseUrl) {
        this.serverBaseUrl = serverBaseUrl;
    }

    public List<ToolDefinition> discoverTools() {
        List<Tool> tools = listTools();
        List<ToolDefinition> definitions = new ArrayList<>();
        for (Tool tool : tools) {
            definitions.add(new ToolDefinition(
                    tool.name(),
                    tool.description(),
                    tool.inputSchema() != null ? tool.inputSchema().toString() : "{}"
            ));
        }
        return definitions;
    }

    public String callTool(String toolName, Map<String, Object> arguments) {
        McpSyncClient client = createClient();
        if (client == null) {
            return "MCP 客户端创建失败";
        }

        try {
            client.initialize();
            CallToolResult result = client.callTool(
                    new CallToolRequest(toolName, arguments));
            if (result.content() != null && !result.content().isEmpty()) {
                Content c = result.content().get(0);
                return c instanceof TextContent tc ? tc.text() : c.toString();
            }
            return "MCP 返回空结果";
        } catch (Exception e) {
            log.error("Failed to call MCP tool {}: {}", toolName, e.getMessage());
            return "MCP 工具调用失败：" + e.getMessage();
        } finally {
            closeQuietly(client);
        }
    }

    public String buildToolsPrompt() {
        List<Tool> tools = listTools();
        if (tools.isEmpty()) {
            return "（无可用的外部工具）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你可以调用以下外部工具：\n\n");
        for (Tool tool : tools) {
            sb.append("工具名称：").append(tool.name()).append("\n");
            sb.append("功能描述：").append(tool.description()).append("\n");
            String schema = tool.inputSchema() != null
                    ? tool.inputSchema().toString() : "{}";
            sb.append("参数定义：").append(schema).append("\n\n");
        }
        return sb.toString();
    }

    private List<Tool> listTools() {
        McpSyncClient client = createClient();
        if (client == null) {
            return Collections.emptyList();
        }

        try {
            client.initialize();
            ListToolsResult result = client.listTools();
            return result.tools();
        } catch (Exception e) {
            log.warn("Failed to discover MCP tools from {}: {}", serverBaseUrl, e.getMessage());
            return Collections.emptyList();
        } finally {
            closeQuietly(client);
        }
    }

    private McpSyncClient createClient() {
        try {
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(serverBaseUrl)
                    .jsonMapper(jsonMapper)
                    .build();
            return io.modelcontextprotocol.client.McpClient.sync(transport).build();
        } catch (Exception e) {
            log.error("Failed to create MCP client: {}", e.getMessage());
            return null;
        }
    }

    private void closeQuietly(McpSyncClient client) {
        try {
            client.closeGracefully();
        } catch (Exception ignored) {
        }
    }

    public record ToolDefinition(String name, String description, String inputSchema) {
    }
}

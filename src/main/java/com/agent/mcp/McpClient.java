package com.agent.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 客户端，通过 HTTP+JSON-RPC 2.0 协议与 MCP Server 通信。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>{@link #discoverTools()} → POST /mcp/tools/list → 获取所有可用工具定义</li>
 *   <li>{@link #callTool(String, Map)} → POST /mcp/tools/call → 执行指定工具</li>
 * </ol>
 * <p>
 * 使用 Jackson 构造符合 JSON-RPC 2.0 规范的请求，解析返回结果。
 * 可配置 serverBaseUrl 连接任意 MCP Server。
 */
@Slf4j
@Component
public class McpClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private String serverBaseUrl = "http://localhost:8080";

    public McpClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.webClient = builder.build();
        this.objectMapper = objectMapper;
    }

    public void setServerBaseUrl(String serverBaseUrl) {
        this.serverBaseUrl = serverBaseUrl;
    }

    /**
     * 获取 MCP Server 上注册的所有工具列表。
     * 遵循 MCP 协议 JSON-RPC 2.0 请求格式。
     *
     * @return 工具定义列表
     */
    public List<ToolDefinition> discoverTools() {
        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");// JSON-RPC 版本
            request.put("id", 1);// 请求 ID
            request.put("method", "tools/list");// 方法名
            request.putObject("params");// 参数为空

            String response = webClient.post()
                    .uri(serverBaseUrl + "/api/mcp/message")
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseToolListResponse(response);// 解析工具列表响应
        } catch (Exception e) {
            log.warn("Failed to discover MCP tools from {}: {}", serverBaseUrl, e.getMessage());
            return List.of();
        }
    }

    /**
     * 调用 MCP Server 上的指定工具。
     *
     * @param toolName  工具名称
     * @param arguments 调用参数
     * @return 工具执行结果文本
     */
    public String callTool(String toolName, Map<String, Object> arguments) {
        try {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", objectMapper.valueToTree(arguments));

            ObjectNode request = objectMapper.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", 2);
            request.put("method", "tools/call");
            request.set("params", params);

            String response = webClient.post()
                    .uri(serverBaseUrl + "/api/mcp/message")
                    .header("Content-Type", "application/json")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseToolCallResponse(response);
        } catch (Exception e) {
            log.error("Failed to call MCP tool {}: {}", toolName, e.getMessage());
            return "MCP 工具调用失败：" + e.getMessage();
        }
    }

    /**
     * 获取格式化的工具描述文本，可直接注入 LLM 的 System Prompt。
     */
    public String buildToolsPrompt() {
        List<ToolDefinition> tools = discoverTools();
        if (tools.isEmpty()) {
            return "（无可用的外部工具）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你可以调用以下外部工具：\n\n");
        for (ToolDefinition tool : tools) {
            sb.append("工具名称：").append(tool.name()).append("\n");
            sb.append("功能描述：").append(tool.description()).append("\n");
            sb.append("参数定义：").append(tool.inputSchema()).append("\n\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<ToolDefinition> parseToolListResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.get("result");
            if (result == null || !result.has("tools")) {
                return List.of();
            }

            List<ToolDefinition> tools = new ArrayList<>();
            for (JsonNode toolNode : result.get("tools")) {
                tools.add(new ToolDefinition(
                        toolNode.get("name").asText(),
                        toolNode.get("description").asText(),
                        toolNode.get("inputSchema").toString()
                ));
            }
            return tools;
        } catch (Exception e) {
            log.warn("Failed to parse tool list: {}", e.getMessage());
            return List.of();
        }
    }

    private String parseToolCallResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode result = root.get("result");
            if (result == null) {
                JsonNode error = root.get("error");
                if (error != null) {
                    return "MCP 错误：" + error.get("message").asText();
                }
                return "MCP 返回空结果";
            }

            JsonNode content = result.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                return content.get(0).get("text").asText();
            }
            return result.toString();
        } catch (Exception e) {
            log.warn("Failed to parse tool call response: {}", e.getMessage());
            return json;
        }
    }

    /**
     * MCP 工具定义，对应 MCP 协议中 tools/list 返回的单条工具描述。
     */
    public record ToolDefinition(String name, String description, String inputSchema) {
    }
}

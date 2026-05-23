package com.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP 协议服务端，通过 HTTP + JSON-RPC 2.0 暴露工具能力。
 * <p>
 * 对外暴露的单一路由 POST /api/mcp/message，按照 MCP 协议规范
 * 分发 tools/list 和 tools/call 两种方法调用。
 * <p>
 * 任何实现了 {@link McpTool} 接口的 Spring Bean 都会被自动注册，
 * 外部 LLM 客户端可通过标准 MCP 协议发现和调用这些工具。
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpServerController {

    private final McpToolRegistry registry;
    private final ObjectMapper objectMapper;

    public McpServerController(List<McpTool> tools, ObjectMapper objectMapper) {
        this.registry = new McpToolRegistry();
        this.objectMapper = objectMapper;
        for (McpTool tool : tools) {
            registry.register(tool);
        }
    }

    @PostConstruct
    public void init() {
        log.info("MCP Server started with {} tools: {}",
                registry.size(),
                registry.listTools().stream().map(McpTool::name).toList());
    }

    /**
     * MCP 协议统一入口，接收 JSON-RPC 2.0 请求，按 method 字段分发。
     *
     * <pre>
     * 请求示例（tools/list）：
     * { "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {} }
     *
     * 请求示例（tools/call）：
     * { "jsonrpc": "2.0", "id": 2, "method": "tools/call",
     *   "params": { "name": "weather_query",
     *               "arguments": { "city": "北京" } } }
     * </pre>
     */
    @PostMapping("/message")
    public ObjectNode handleMessage(@RequestBody JsonNode request) {
        String method = getString(request, "method");
        int id = request.has("id") ? request.get("id").asInt() : 0;

        log.debug("MCP request: method={}, id={}", method, id);

        try {
            return switch (method) {
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, request.get("params"));
                case "initialize" -> handleInitialize(id);
                default -> errorResponse(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.error("MCP error: {}", e.getMessage(), e);
            return errorResponse(id, -32603, e.getMessage());
        }
    }

    private ObjectNode handleToolsList(int id) {
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (McpTool tool : registry.listTools()) {
            ObjectNode toolNode = objectMapper.createObjectNode();
            toolNode.put("name", tool.name());
            toolNode.put("description", tool.description());
            try {
                toolNode.set("inputSchema", objectMapper.readTree(tool.inputSchema()));
            } catch (Exception e) {
                toolNode.putObject("inputSchema");
            }
            toolsArray.add(toolNode);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", toolsArray);

        return successResponse(id, result);
    }

    @SuppressWarnings("unchecked")
    private ObjectNode handleToolsCall(int id, JsonNode params) {
        String name = getString(params, "name");
        Map<String, Object> arguments = Map.of();
        if (params.has("arguments") && !params.get("arguments").isNull()) {
            try {
                arguments = objectMapper.convertValue(params.get("arguments"), Map.class);
            } catch (Exception e) {
                return errorResponse(id, -32602, "Invalid arguments: " + e.getMessage());
            }
        }

        log.info("MCP tools/call: name={}, arguments={}", name, arguments);

        McpToolResult result = registry.call(name, arguments);

        ObjectNode resultNode = objectMapper.createObjectNode();
        ArrayNode contentArray = objectMapper.createArrayNode();
        for (McpToolResult.TextContent tc : result.content()) {
            ObjectNode textNode = objectMapper.createObjectNode();
            textNode.put("type", tc.type());
            textNode.put("text", tc.text());
            contentArray.add(textNode);
        }
        resultNode.set("content", contentArray);
        resultNode.put("isError", result.isError());

        return successResponse(id, resultNode);
    }

    private ObjectNode handleInitialize(int id) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.put("serverInfo", objectMapper.createObjectNode()
                .put("name", "agent-kb-qa-mcp")
                .put("version", "1.0.0"));

        ObjectNode capabilities = objectMapper.createObjectNode();
        capabilities.putObject("tools");
        result.set("capabilities", capabilities);

        return successResponse(id, result);
    }

    private ObjectNode successResponse(int id, ObjectNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.set("result", result);
        return response;
    }

    private ObjectNode errorResponse(int id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }

    private String getString(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
    }
}

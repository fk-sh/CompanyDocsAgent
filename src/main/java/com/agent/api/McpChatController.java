package com.agent.api;

import com.agent.service.McpAwareChatService;
import com.agent.service.McpAwareChatService.McpChatResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 感知的对话接口 — LLM 自主发现和调用 MCP 工具。
 * <p>
 * 与 {@code /api/test/chat} 的关键区别：
 * <ul>
 *   <li>这里 LLM 先通过 MCP 协议发现可用工具（tools/list）</li>
 *   <li>LLM 自主判断是否需要调用工具、调用哪个工具</li>
 *   <li>工具执行结果会返回给 LLM 继续推理</li>
 *   <li>最终答案由 LLM 综合所有信息生成</li>
 * </ul>
 * <p>
 * 请求示例：
 * <pre>
 * POST /api/mcp/chat
 * { "message": "北京今天天气怎么样" }
 * </pre>
 * 响应示例：
 * <pre>
 * {
 *   "answer": "北京今天天气晴朗，温度 22°C...",
 *   "toolCalls": "调用工具：weather_query，参数：{city=北京}\n工具返回：北京天气：Clear 22°C..."
 * }
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
public class McpChatController {

    private final McpAwareChatService mcpChatService;

    public McpChatController(McpAwareChatService mcpChatService) {
        this.mcpChatService = mcpChatService;
    }

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "你好");

        log.info("MCP Chat request: {}", message);
        McpChatResult result = mcpChatService.chat(message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", result.answer());
        response.put("toolCalls", result.toolCallTrace());
        return response;
    }

    @GetMapping("/chat")
    public Map<String, Object> chatGet(@RequestParam(defaultValue = "你好") String message) {
        McpChatResult result = mcpChatService.chat(message);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("answer", result.answer());
        response.put("toolCalls", result.toolCallTrace());
        return response;
    }
}

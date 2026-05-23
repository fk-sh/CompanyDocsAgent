package com.agent.service;

import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import com.agent.mcp.McpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 感知的对话服务 — 真正的 LLM 驱动工具调用。
 * <p>
 * 核心思路（ReAct 循环）：
 * <ol>
 *   <li>通过 {@link McpClient} 发现所有 MCP 工具，生成工具描述注入 System Prompt</li>
 *   <li>LLM 看到工具列表后，自主决定是否需要调用、调用哪个工具</li>
 *   <li>如果 LLM 输出工具调用指令，执行之并将结果喂回 LLM</li>
 *   <li>LLM 根据工具结果生成最终答案</li>
 * </ol>
 * <p>
 * 这不再是"Agent 硬编码决定调用哪个工具"，而是 LLM 自主决策 — 这才是真正的 MCP / Function Calling。
 */
@Slf4j
@Service
public class McpAwareChatService {

    private final DeepSeekChatClient llm;
    private final McpClient mcpClient;
    private final ObjectMapper objectMapper;

    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "<tool_call>\\s*<name>(.+?)</name>\\s*<arguments>(.+?)</arguments>\\s*</tool_call>",
            Pattern.DOTALL
    );

    private static final int MAX_LOOPS = 5;

    public McpAwareChatService(DeepSeekChatClient llm, McpClient mcpClient, ObjectMapper objectMapper) {
        this.llm = llm;
        this.mcpClient = mcpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * MCP 工具感知的对话。
     * <p>
     * LLM 在 System Prompt 中看到完整的工具列表（名称/描述/JSON Schema），
     * 自主判断是否需要调用工具。如果需要，以 XML 格式输出工具调用指令，
     * 系统执行后把结果喂回 LLM，LLM 再生成最终答案。
     *
     * @param userMessage 用户输入
     * @return 包含最终答案和工具调用轨迹的结果
     */
    public McpChatResult chat(String userMessage) {
        String toolsPrompt = mcpClient.buildToolsPrompt();
        String systemPrompt = buildSystemPrompt(toolsPrompt);

        List<Message> conversation = new ArrayList<>();
        conversation.add(Message.user(userMessage));

        StringBuilder toolCallTrace = new StringBuilder();
        int loop = 0;

        while (loop < MAX_LOOPS) {
            loop++;
            log.info("McpAwareChat ReAct loop {}/{}", loop, MAX_LOOPS);

            List<Message> fullMessages = new ArrayList<>();
            fullMessages.add(Message.system(systemPrompt));
            fullMessages.addAll(conversation);

            String response = llm.chat(fullMessages);

            ToolCallMatch match = parseToolCall(response);

            if (match != null) {
                log.info("LLM decided to call tool: {} with args: {}", match.toolName, match.arguments);
                toolCallTrace.append("调用工具：").append(match.toolName)
                        .append("，参数：").append(match.arguments).append("\n");

                conversation.add(Message.assistant(response));// 添加 LLM 回答

                String toolResult = mcpClient.callTool(match.toolName, match.arguments);// 调用工具
                toolCallTrace.append("工具返回：").append(toolResult).append("\n");

                conversation.add(Message.tool(match.toolName, toolResult));
            } else {
                log.info("LLM returned final answer (no tool call detected)");// LLM 直接返回最终答案
                conversation.add(Message.assistant(response));// 添加 LLM 回答
                return new McpChatResult(response, toolCallTrace.toString());// 返回最终结果
            }
        }

        log.warn("Reached max loops ({}), forcing final summary", MAX_LOOPS);
        List<Message> finalMessages = new ArrayList<>();
        finalMessages.add(Message.system(systemPrompt));
        finalMessages.addAll(conversation);
        finalMessages.add(Message.user("请根据以上所有工具调用的结果，直接给出最终答案，不要再调用工具。"));
        String finalAnswer = llm.chat(finalMessages);
        return new McpChatResult(finalAnswer, toolCallTrace.toString());
    }

    private String buildSystemPrompt(String toolsPrompt) {
        return """
                你是一个智能助手，可以调用外部工具来获取实时信息。

                %s

                当你需要调用工具获取信息时，请严格按照以下XML格式输出工具调用请求：
                <tool_call>
                <name>工具名称</name>
                <arguments>{"参数名":"参数值"}</arguments>
                </tool_call>

                系统会自动执行工具调用并将结果返回给你，然后你再根据结果回答用户。
                如果不需要调用工具或已经拿到足够信息，请直接给出回答，不要输出XML标签。
                """.formatted(toolsPrompt);
    }

    private ToolCallMatch parseToolCall(String response) {
        Matcher m = TOOL_CALL_PATTERN.matcher(response);
        if (m.find()) {
            String toolName = m.group(1).trim();
            Map<String, Object> args = parseArguments(m.group(2).trim());
            return new ToolCallMatch(toolName, args);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String argsJson) {
        try {
            return objectMapper.readValue(argsJson, Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse tool arguments: {}", argsJson, e);
            return Map.of();
        }
    }

    private record ToolCallMatch(String toolName, Map<String, Object> arguments) {}

    public record McpChatResult(String answer, String toolCallTrace) {
    }
}

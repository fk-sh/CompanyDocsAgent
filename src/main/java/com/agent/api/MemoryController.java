package com.agent.api;

import com.agent.core.Message;
import com.agent.memory.AgentSession;
import com.agent.memory.MemoryManager;
import com.agent.service.SimpleChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆模块测试控制器，提供 REST API 验证记忆体系全部功能。
 * <p>
 * <b>端点说明</b>：
 * <table border="1">
 *   <tr><th>端点</th><th>对应的记忆操作</th></tr>
 *   <tr><td>{@code POST /sessions}</td><td>创建新会话 → MySQL agent_sessions</td></tr>
 *   <tr><td>{@code GET /sessions}</td><td>按 userId 分页查询会话列表</td></tr>
 *   <tr><td>{@code GET /sessions/{id}}</td><td>会话详情 + 消息列表</td></tr>
 *   <tr><td>{@code POST /chat}</td><td>完整对话流程：记忆写入 → LLM 对话 → 用户画像提取</td></tr>
 *   <tr><td>{@code POST /sessions/{id}/end}</td><td>归档：STATUS=ARCHIVED → 清空短期记忆</td></tr>
 *   <tr><td>{@code DELETE /sessions/{id}}</td><td>级联删除：MySQL + ES + 内存</td></tr>
 * </table>
 * <p>
 * 注意：这是一个测试用 Controller，Phase 10 会被正式的 {@code ChatController} 取代。
 *
 * @see MemoryManager
 * @see SimpleChatService
 */
@Slf4j
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryManager memoryManager;
    private final SimpleChatService chatService;

    public MemoryController(MemoryManager memoryManager, SimpleChatService chatService) {
        this.memoryManager = memoryManager;
        this.chatService = chatService;
    }

    /**
     * 创建新会话。
     *
     * @param body 可选参数：userId (默认 test-user)、title (默认"新对话")
     * @return sessionId + title + userId
     */
    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody(required = false) Map<String, String> body) {
        String userId = body != null ? body.getOrDefault("userId", "test-user") : "test-user";
        String title = body != null ? body.getOrDefault("title", "新对话") : "新对话";
        String sessionId = memoryManager.createSession(userId, title);
        return Map.of("sessionId", sessionId, "title", title, "userId", userId);
    }

    /**
     * 在指定会话中发送消息并获取 LLM 回复。
     * <p>
     * 执行流程：
     * <ol>
     *   <li>用户消息写入三层记忆</li>
     *   <li>{@link MemoryManager#buildContext} 构建上下文（ES 检索 + 用户画像 + 近期对话）</li>
     *   <li>调用 DeepSeek 获取回复</li>
     *   <li>助手回复写入三层记忆</li>
     *   <li>异步提取用户画像（城市、角色、兴趣等）</li>
     * </ol>
     *
     * @param body 必选参数：sessionId + message
     * @return reply + historySize + memoryContext(截断300字) + userProfile(如有)
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        String sessionId = (String) body.getOrDefault("sessionId", "default");
        String message = (String) body.getOrDefault("message", "你好");

        log.info("Chat [{}]: {}", sessionId, message);

        memoryManager.saveUserMessage(sessionId, message);

        var ctx = memoryManager.buildContext(sessionId, message);
        String memoryContext = ctx.getVariable("memoryContext", "");
        String userProfile = ctx.getVariable("userProfile", "");

        String reply = chatService.chat(message);

        memoryManager.saveAssistantMessage(sessionId, reply);
        memoryManager.extractUserProfile(sessionId, message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("userMessage", message);
        result.put("reply", reply);
        result.put("historySize", historySize(sessionId));
        if (!memoryContext.isEmpty()) {
            result.put("memoryContext", memoryContext.length() > 300
                    ? memoryContext.substring(0, 300) + "..." : memoryContext);
        }
        if (!userProfile.isEmpty()) {
            result.put("userProfile", userProfile);
        }
        return result;
    }

    /**
     * 查询会话详情（含消息列表）。
     */
    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> getSession(@PathVariable String sessionId) {
        var sessionOpt = memoryManager.getSession(sessionId);
        if (sessionOpt.isEmpty()) {
            return Map.of("error", "Session not found");
        }
        var session = sessionOpt.get();
        List<Message> messages = memoryManager.getAllMessages();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", Map.of(
                "id", session.getId(),
                "title", session.getTitle(),
                "status", session.getStatus(),
                "createdAt", session.getCreatedAt().toString()
        ));
        result.put("messageCount", messages.size());
        result.put("messages", messages.stream()
                .map(m -> Map.of(
                        "role", m.getRole().name(),
                        "content", m.getContent().length() > 200
                                ? m.getContent().substring(0, 200) + "..." : m.getContent(),
                        "timestamp", m.getTimestamp() != null ? m.getTimestamp().toString() : ""
                ))
                .toList());
        return result;
    }

    /**
     * 按 userId 分页查询会话列表，按更新时间降序。
     */
    @GetMapping("/sessions")
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "test-user") String userId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<AgentSession> sessions = memoryManager.getUserSessions(userId, limit, offset);
        return Map.of(
                "userId", userId,
                "sessions", sessions.stream()
                        .map(s -> Map.of(
                                "id", s.getId(),
                                "title", s.getTitle(),
                                "status", s.getStatus(),
                                "createdAt", s.getCreatedAt().toString()
                        ))
                        .toList()
        );
    }

    /**
     * 级联删除会话：MySQL sessions + messages + ES 长期记忆 + 清空内存。
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        memoryManager.deleteSession(sessionId);
        return Map.of("status", "deleted", "sessionId", sessionId);
    }

    /**
     * 归档会话：状态改为 ARCHIVED + 清空短期记忆。
     */
    @PostMapping("/sessions/{sessionId}/end")
    public Map<String, String> endSession(@PathVariable String sessionId) {
        memoryManager.endSession(sessionId);
        return Map.of("status", "archived", "sessionId", sessionId);
    }

    /**
     * 辅助方法：加载会话记忆后返回当前消息总数。
     */
    private int historySize(String sessionId) {
        memoryManager.loadSessionMemory(sessionId);
        return memoryManager.getAllMessages().size();
    }
}

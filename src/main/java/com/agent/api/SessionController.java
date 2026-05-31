package com.agent.api;

import com.agent.api.dto.SessionRequest;
import com.agent.api.dto.SessionResponse;
import com.agent.auth.CurrentUser;
import com.agent.auth.CurrentUserHolder;
import com.agent.core.Message;
import com.agent.memory.AgentSession;
import com.agent.memory.MemoryManager;
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

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class SessionController {

    private final MemoryManager memoryManager;

    public SessionController(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    @PostMapping("/sessions")
    public SessionResponse createSession(@RequestBody SessionRequest request) {
        CurrentUser currentUser = CurrentUserHolder.require();
        String userId = currentUser.getId();
        String title = request.getTitle() != null ? request.getTitle() : "新对话";

        String sessionId = memoryManager.createSession(userId, title);

        AgentSession session = memoryManager.getSession(sessionId).orElse(null);

        return SessionResponse.builder()
                .sessionId(sessionId)
                .userId(userId)
                .title(title)
                .status(session != null ? session.getStatus() : "ACTIVE")
                .createdAt(session != null ? session.getCreatedAt().toString() : "")
                .updatedAt(session != null ? session.getUpdatedAt().toString() : "")
                .build();
    }

    @GetMapping("/sessions")
    public List<SessionResponse> listSessions(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        CurrentUser currentUser = CurrentUserHolder.require();
        String userId = currentUser.getId();
        log.info("GET /sessions userId={}, limit={}, offset={}", userId, limit, offset);

        List<AgentSession> sessions = memoryManager.getUserSessions(userId, limit, offset);

        return sessions.stream()
                .map(s -> SessionResponse.builder()
                        .sessionId(s.getId())
                        .userId(s.getUserId())
                        .title(s.getTitle())
                        .status(s.getStatus())
                        .createdAt(s.getCreatedAt().toString())
                        .updatedAt(s.getUpdatedAt().toString())
                        .build())
                .toList();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Map<String, Object> getSessionMessages(@PathVariable String sessionId,
                                                  @RequestParam(defaultValue = "100") int limit) {
        CurrentUser currentUser = CurrentUserHolder.require();
        AgentSession session = memoryManager.getSession(sessionId).orElse(null);
        if (session == null || !currentUser.getId().equals(session.getUserId())) {
            throw new IllegalArgumentException("会话不存在");
        }
        log.info("GET /sessions/{}/messages limit={}", sessionId, limit);
        List<Message> messages = memoryManager.getSessionMessages(sessionId, limit);

        List<Map<String, Object>> msgList = messages.stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("role", m.getRole().name().toLowerCase());
                    map.put("content", m.getContent());
                    map.put("timestamp", m.getTimestamp() != null ? m.getTimestamp().toString() : "");
                    return map;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("messages", msgList);
        return result;
    }

    @DeleteMapping("/sessions/{sessionId}")
    public java.util.Map<String, String> deleteSession(@PathVariable String sessionId) {
        CurrentUser currentUser = CurrentUserHolder.require();
        AgentSession session = memoryManager.getSession(sessionId).orElse(null);
        if (session == null || !currentUser.getId().equals(session.getUserId())) {
            throw new IllegalArgumentException("会话不存在");
        }
        log.info("DELETE /sessions/{}", sessionId);
        memoryManager.deleteSession(sessionId);
        return java.util.Map.of("status", "deleted", "sessionId", sessionId);
    }
}

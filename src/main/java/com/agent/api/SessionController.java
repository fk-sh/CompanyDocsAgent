package com.agent.api;

import com.agent.api.dto.SessionRequest;
import com.agent.api.dto.SessionResponse;
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

import java.util.List;

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
        String userId = request.getUserId() != null ? request.getUserId() : "default-user";
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
            @RequestParam(defaultValue = "default-user") String userId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {

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

    @DeleteMapping("/sessions/{sessionId}")
    public java.util.Map<String, String> deleteSession(@PathVariable String sessionId) {
        log.info("DELETE /sessions/{}", sessionId);
        memoryManager.deleteSession(sessionId);
        return java.util.Map.of("status", "deleted", "sessionId", sessionId);
    }
}

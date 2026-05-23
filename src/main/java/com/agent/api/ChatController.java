package com.agent.api;

import com.agent.api.dto.ChatRequest;
import com.agent.api.dto.ChatResponse;
import com.agent.api.dto.DocumentResponse;
import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Chunk;
import com.agent.ingestion.FullIngestionPipeline;
import com.agent.llm.DeepSeekStreamingClient;
import com.agent.memory.MemoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final Agent orchestratorAgent;
    private final MemoryManager memoryManager;
    private final DeepSeekStreamingClient streamingClient;

    private final Map<String, Map<String, Object>> documentStore = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> feedbackStore = new ConcurrentHashMap<>();

    public ChatController(@Qualifier("orchestratorAgent") Agent orchestratorAgent,
                          MemoryManager memoryManager,
                          DeepSeekStreamingClient streamingClient) {
        this.orchestratorAgent = orchestratorAgent;
        this.memoryManager = memoryManager;
        this.streamingClient = streamingClient;
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : "sess-" + UUID.randomUUID().toString().substring(0, 8);
        String query = request.getQuery();

        if (query == null || query.isEmpty()) {
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .answer("query 不能为空")
                    .build();
        }

        log.info("POST /chat session={}, query={}", sessionId, query);

        memoryManager.saveUserMessage(sessionId, query);

        long start = System.currentTimeMillis();
        AgentContext ctx = memoryManager.buildContext(sessionId, query);
        orchestratorAgent.execute(ctx);

        String answer = ctx.getVariable("finalAnswer", "");
        if (answer.isEmpty()) {
            answer = ctx.getVariable("answer", "抱歉，无法处理您的问题。");
        }
        long latencyMs = System.currentTimeMillis() - start;

        String intent = ctx.getVariable("intent", "");
        List<String> contexts = extractContexts(ctx);

        memoryManager.saveAssistantMessage(sessionId, answer);

        return ChatResponse.builder()
                .sessionId(sessionId)
                .query(query)
                .answer(answer)
                .contexts(contexts.isEmpty() ? null : contexts)
                .latencyMs(latencyMs)
                .intent(intent.isEmpty() ? null : intent)
                .build();
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String query,
                                   @RequestParam(defaultValue = "") String sessionId) {
        String sid = sessionId.isEmpty()
                ? "sess-" + UUID.randomUUID().toString().substring(0, 8)
                : sessionId;

        log.info("GET /chat/stream session={}, query={}", sid, query);

        memoryManager.saveUserMessage(sid, query);

        return Flux.concat(
                Flux.just("event: session\n" + "data: " + sid + "\n\n"),
                streamingClient.stream(query)
                        .doOnComplete(() -> {
                            log.info("Stream completed for session={}", sid);
                        })
                        .doOnError(e -> log.error("Stream error for session={}: {}", sid, e.getMessage()))
        );
    }

    @PostMapping("/documents/upload")
    public DocumentResponse uploadDocument(@RequestParam("file") MultipartFile file) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);

        log.info("POST /documents/upload fileName={}, size={}, taskId={}",
                file.getOriginalFilename(), file.getSize(), taskId);

        String documentId = UUID.randomUUID().toString().replace("-", "");

        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "agent-uploads");
            Files.createDirectories(tempDir);
            Path tempFile = tempDir.resolve(taskId + "_" + file.getOriginalFilename());
            file.transferTo(tempFile.toFile());

            Map<String, Object> docInfo = new LinkedHashMap<>();
            docInfo.put("documentId", documentId);
            docInfo.put("fileName", file.getOriginalFilename());
            docInfo.put("size", file.getSize());
            docInfo.put("taskId", taskId);
            docInfo.put("filePath", tempFile.toString());
            docInfo.put("status", "PROCESSING");
            docInfo.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            documentStore.put(documentId, docInfo);

            AgentContext ctx = new AgentContext("ingest-" + taskId, "");
            ctx.setVariable("filePath", tempFile.toString());
            ctx.setVariable("intent", "doc_ingestion");
            ctx.setVariable("taskId", taskId);
            orchestratorAgent.execute(ctx);

            String ingestionStatus = ctx.getVariable("ingestionStatus", "PROCESSING");
            docInfo.put("status", ingestionStatus);
            documentStore.put(documentId, docInfo);

            log.info("Document {} ingestion status: {}", documentId, ingestionStatus);

        } catch (IOException e) {
            log.error("Document upload failed: {}", e.getMessage());
            Map<String, Object> docInfo = new LinkedHashMap<>();
            docInfo.put("documentId", documentId);
            docInfo.put("fileName", file.getOriginalFilename());
            docInfo.put("size", file.getSize());
            docInfo.put("taskId", taskId);
            docInfo.put("status", "FAILED: " + e.getMessage());
            docInfo.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            documentStore.put(documentId, docInfo);
        }

        Map<String, Object> docInfo = documentStore.get(documentId);
        return DocumentResponse.builder()
                .documentId(documentId)
                .fileName((String) docInfo.get("fileName"))
                .status((String) docInfo.get("status"))
                .taskId(taskId)
                .size((Long) docInfo.get("size"))
                .createdAt((String) docInfo.get("createdAt"))
                .build();
    }

    @GetMapping("/documents")
    public List<DocumentResponse> listDocuments() {
        log.info("GET /documents, count={}", documentStore.size());
        return documentStore.values().stream()
                .map(doc -> DocumentResponse.builder()
                        .documentId((String) doc.get("documentId"))
                        .fileName((String) doc.get("fileName"))
                        .status((String) doc.get("status"))
                        .taskId((String) doc.get("taskId"))
                        .size((Long) doc.get("size"))
                        .createdAt((String) doc.get("createdAt"))
                        .build())
                .toList();
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, String> deleteDocument(@PathVariable String id) {
        log.info("DELETE /documents/{}", id);
        Map<String, Object> removed = documentStore.remove(id);
        if (removed != null) {
            return Map.of("status", "deleted", "documentId", id);
        }
        return Map.of("error", "document not found", "documentId", id);
    }

    private List<String> extractContexts(AgentContext ctx) {
        List<String> contexts = new ArrayList<>();
        String retrievedContext = ctx.getVariable("retrievedContext", "");
        if (!retrievedContext.isEmpty()) {
            String[] parts = retrievedContext.split("\n---\n");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    contexts.add(trimmed);
                }
            }
        }
        return contexts;
    }
}

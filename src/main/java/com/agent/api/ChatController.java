package com.agent.api;

import com.agent.agent.OrchestratorAgent;
import com.agent.api.dto.ChatRequest;
import com.agent.api.dto.ChatResponse;
import com.agent.api.dto.DocumentResponse;
import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Document;
import com.agent.ingestion.FullIngestionPipeline;
import com.agent.memory.MemoryManager;
import com.agent.mq.DocumentIngestionMessage;
import com.agent.mq.DocumentIngestionProducer;
import com.agent.mq.IngestionStatusStore;
import com.agent.memory.preference.PreferenceMemoryExtractor;
import com.agent.upload.ChunkUploadManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final OrchestratorAgent orchestratorAgent;
    private final MemoryManager memoryManager;
    private final PreferenceMemoryExtractor preferenceExtractor;

    private final Map<String, Map<String, Object>> feedbackStore = new LinkedHashMap<>();

    @Autowired(required = false)
    private DocumentIngestionProducer ingestionProducer;

    @Autowired
    private IngestionStatusStore statusStore;

    @Autowired
    private ChunkUploadManager chunkUploadManager;

    @Autowired(required = false)
    private FullIngestionPipeline ingestionPipeline;

    public ChatController(@Qualifier("orchestratorAgent") Agent orchestratorAgent,
                          MemoryManager memoryManager,
                          PreferenceMemoryExtractor preferenceExtractor) {
        this.orchestratorAgent = (OrchestratorAgent) orchestratorAgent;
        this.memoryManager = memoryManager;
        this.preferenceExtractor = preferenceExtractor;
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

        String userId = memoryManager.resolveUserId(sessionId);
        preferenceExtractor.extractAndStoreAsync(userId, query);

        long start = System.currentTimeMillis();
        AgentContext ctx = memoryManager.buildContext(sessionId, query);
        String answer = orchestratorAgent.execute(ctx);

        long latencyMs = System.currentTimeMillis() - start;

        List<String> contexts = extractContexts(ctx);

        memoryManager.saveAssistantMessage(sessionId, answer);

        return ChatResponse.builder()
                .sessionId(sessionId)
                .query(query)
                .answer(answer)
                .contexts(contexts.isEmpty() ? null : contexts)
                .latencyMs(latencyMs)
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

        String uid = memoryManager.resolveUserId(sid);
        preferenceExtractor.extractAndStoreAsync(uid, query);

        AgentContext ctx = memoryManager.buildContext(sid, query);

        StringBuilder fullAnswer = new StringBuilder();
        String finalSid = sid;

        return orchestratorAgent.executeStream(ctx)
                .doOnNext(fullAnswer::append)
                .doOnComplete(() -> {
                    String answer = fullAnswer.toString();
                    if (!answer.isEmpty()) {
                        memoryManager.saveAssistantMessage(finalSid, answer);
                    }
                    memoryManager.updateSessionTitle(finalSid,
                            query.length() > 30 ? query.substring(0, 30) + "\u2026" : query);
                    log.info("Stream completed for session={}", finalSid);
                })
                .doOnError(e -> log.error("Stream error for session={}: {}", finalSid, e.getMessage()));
    }

    @PostMapping("/documents/upload")
    public DocumentResponse uploadDocument(@RequestParam("file") MultipartFile file) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String fileName = file.getOriginalFilename();

        log.info("POST /documents/upload fileName={}, size={}, taskId={}", fileName, file.getSize(), taskId);

        String documentId = UUID.randomUUID().toString().replace("-", "");

        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "agent-uploads");
            Files.createDirectories(tempDir);
            Path tempFile = tempDir.resolve(taskId + "_" + fileName);
            file.transferTo(tempFile.toFile());

            String fileType = fileName != null && fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                    : "unknown";

            Map<String, Object> docInfo = new LinkedHashMap<>();
            docInfo.put("documentId", documentId);
            docInfo.put("fileName", fileName);
            docInfo.put("size", file.getSize());
            docInfo.put("taskId", taskId);
            docInfo.put("fileType", fileType);
            docInfo.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            if (ingestionProducer != null) {
                DocumentIngestionMessage message = DocumentIngestionMessage.builder()
                        .documentId(documentId)
                        .taskId(taskId)
                        .fileName(fileName)
                        .fileType(fileType)
                        .filePath(tempFile.toString())
                        .fileSize(file.getSize())
                        .createdAt(Instant.now().toEpochMilli())
                        .build();

                boolean sent = ingestionProducer.send(message);
                if (sent) {
                    docInfo.put("status", "ACCEPTED");
                    log.info("Document {} sent to RocketMQ: {}", documentId, fileName);
                } else {
                    doDirectIngestion(documentId, fileName, fileType, tempFile, docInfo);
                }
            } else {
                doDirectIngestion(documentId, fileName, fileType, tempFile, docInfo);
            }

            statusStore.create(documentId, docInfo);

        } catch (Exception e) {
            log.error("Document upload failed: {}", e.getMessage(), e);
            Map<String, Object> docInfo = new LinkedHashMap<>();
            docInfo.put("documentId", documentId);
            docInfo.put("fileName", fileName);
            docInfo.put("size", file.getSize());
            docInfo.put("taskId", taskId);
            docInfo.put("status", "FAILED: " + e.getMessage());
            docInfo.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            statusStore.create(documentId, docInfo);
        }

        Map<String, Object> docInfo = statusStore.get(documentId);
        if (docInfo == null) {
            return DocumentResponse.builder()
                    .documentId(documentId)
                    .fileName(fileName)
                    .status("UNKNOWN")
                    .taskId(taskId)
                    .size(file.getSize())
                    .createdAt("")
                    .build();
        }
        return DocumentResponse.builder()
                .documentId(documentId)
                .fileName(fileName)
                .status((String) docInfo.get("status"))
                .taskId(taskId)
                .size(file.getSize())
                .createdAt((String) docInfo.get("createdAt"))
                .build();
    }

    @GetMapping("/documents")
    public List<DocumentResponse> listDocuments() {
        log.info("GET /documents, count={}", statusStore.size());
        return statusStore.all().stream()
                .map(doc -> DocumentResponse.builder()
                        .documentId((String) doc.get("documentId"))
                        .fileName((String) doc.get("fileName"))
                        .status((String) doc.getOrDefault("status", "UNKNOWN"))
                        .taskId((String) doc.get("taskId"))
                        .size(doc.get("size") instanceof Long s ? s : 0L)
                        .createdAt((String) doc.get("createdAt"))
                        .build())
                .toList();
    }

    @GetMapping("/documents/{id}")
    public DocumentResponse getDocument(@PathVariable String id) {
        log.info("GET /documents/{}", id);
        Map<String, Object> docInfo = statusStore.get(id);
        if (docInfo == null) {
            return null;
        }
        return DocumentResponse.builder()
                .documentId(id)
                .fileName((String) docInfo.get("fileName"))
                .status((String) docInfo.getOrDefault("status", "UNKNOWN"))
                .taskId((String) docInfo.get("taskId"))
                .size(docInfo.get("size") instanceof Long s ? s : 0L)
                .createdAt((String) docInfo.get("createdAt"))
                .build();
    }

    @GetMapping("/documents/failed")
    public List<DocumentResponse> listDeadLettered() {
        List<Map<String, Object>> deadLettered = statusStore.findDeadLettered();
        log.info("GET /documents/failed, count={}", deadLettered.size());
        return deadLettered.stream()
                .map(doc -> DocumentResponse.builder()
                        .documentId((String) doc.get("documentId"))
                        .fileName((String) doc.get("fileName"))
                        .status("DEAD_LETTERED")
                        .taskId((String) doc.get("taskId"))
                        .size(doc.get("size") instanceof Long s ? s : 0L)
                        .createdAt((String) doc.get("createdAt"))
                        .build())
                .toList();
    }

    @PostMapping("/documents/{id}/retry")
    public Map<String, Object> retryDocument(@PathVariable String id) {
        log.info("POST /documents/{}/retry", id);

        Map<String, Object> docInfo = statusStore.get(id);
        if (docInfo == null) {
            return Map.of("error", "document not found", "documentId", id);
        }

        String currentStatus = (String) docInfo.getOrDefault("status", "");
        if (!"DEAD_LETTERED".equals(currentStatus) && !currentStatus.startsWith("FAILED")) {
            return Map.of("error", "document is not in a failed state", "documentId", id,
                    "currentStatus", currentStatus);
        }

        if (ingestionProducer == null) {
            return Map.of("error", "ingestion producer not available", "documentId", id);
        }

        String fileName = (String) docInfo.getOrDefault("fileName", "unknown");
        String fileType = (String) docInfo.getOrDefault("fileType",
                fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "unknown");
        String filePath = (String) docInfo.getOrDefault("filePath", "");

        statusStore.resetForRetry(id);

        DocumentIngestionMessage message = DocumentIngestionMessage.builder()
                .documentId(id)
                .taskId((String) docInfo.getOrDefault("taskId", id.substring(0, 8)))
                .fileName(fileName)
                .fileType(fileType)
                .filePath(filePath)
                .fileSize(docInfo.get("size") instanceof Long s ? s : 0L)
                .createdAt(Instant.now().toEpochMilli())
                .retryCount(0)
                .build();

        boolean sent = ingestionProducer.send(message);

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("documentId", id);
        response.put("status", sent ? "RETRY_SUBMITTED" : "RETRY_FAILED");
        response.put("message", sent ? "Re-submitted to ingestion queue" : "Failed to re-submit");
        return response;
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, String> deleteDocument(@PathVariable String id) {
        log.info("DELETE /documents/{}", id);
        Map<String, Object> removed = statusStore.remove(id);
        if (removed != null) {
            return Map.of("status", "deleted", "documentId", id);
        }
        return Map.of("error", "document not found", "documentId", id);
    }

    @PostMapping("/documents/chunk/init")
    public Map<String, Object> initChunkUpload(@RequestBody Map<String, Object> body) {
        String fileName = (String) body.get("fileName");
        long fileSize = body.get("fileSize") instanceof Number n ? n.longValue() : 0L;
        int totalChunks = body.get("totalChunks") instanceof Number n ? n.intValue() : 0;
        int chunkSize = body.get("chunkSize") instanceof Number n ? n.intValue() : 2 * 1024 * 1024;
        String fileHash = (String) body.get("fileHash");
        if (fileHash == null || fileHash.isEmpty()) {
            fileHash = UUID.randomUUID().toString();
        }

        try {
            ChunkUploadManager.ResumeInfo resume = chunkUploadManager.findOrCreateSession(
                    fileHash, fileName, fileSize, totalChunks, chunkSize);

            log.info("Chunk upload init: uploadId={}, file={}, size={}, chunks={}, isNew={}, resume={}",
                    resume.uploadId, fileName, fileSize, totalChunks,
                    resume.isNew, resume.completedChunks.size());

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("uploadId", resume.uploadId);
            result.put("chunkSize", chunkSize);
            result.put("totalChunks", totalChunks);
            result.put("isNew", resume.isNew);
            result.put("completedChunks", resume.completedChunks);
            return result;
        } catch (Exception e) {
            log.error("Failed to init chunk upload: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/documents/chunk/{uploadId}")
    public Map<String, Object> uploadChunk(@PathVariable String uploadId,
                                           @RequestParam("chunkIndex") int chunkIndex,
                                           @RequestParam("file") MultipartFile file) {
        try {
            byte[] data = file.getBytes();
            chunkUploadManager.writeChunk(uploadId, chunkIndex, data);
            Map<String, Object> progress = chunkUploadManager.getProgress(uploadId);
            log.debug("Chunk uploaded: uploadId={}, chunk={}", uploadId, chunkIndex);
            return progress;
        } catch (Exception e) {
            log.error("Failed to upload chunk: uploadId={}, chunk={}: {}", uploadId, chunkIndex, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/documents/chunk/{uploadId}/progress")
    public Map<String, Object> chunkProgress(@PathVariable String uploadId) {
        return chunkUploadManager.getProgress(uploadId);
    }

    @PostMapping("/documents/chunk/{uploadId}/complete")
    public DocumentResponse completeChunkUpload(@PathVariable String uploadId) {
        ChunkUploadManager.UploadSession session = chunkUploadManager.getSession(uploadId);
        if (session == null) {
            return DocumentResponse.builder()
                    .documentId(uploadId)
                    .fileName("unknown")
                    .status("NOT_FOUND")
                    .build();
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String fileName = session.fileName;
        String documentId = UUID.randomUUID().toString().replace("-", "");

        try {
            chunkUploadManager.complete(uploadId);

            Path mergedFile = session.tempFile;
            long fileSize = Files.size(mergedFile);

            String fileType = fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                    : "unknown";

            Map<String, Object> docInfo = new LinkedHashMap<>();
            docInfo.put("documentId", documentId);
            docInfo.put("fileName", fileName);
            docInfo.put("size", fileSize);
            docInfo.put("taskId", taskId);
            docInfo.put("fileType", fileType);
            docInfo.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            if (ingestionProducer != null) {
                DocumentIngestionMessage message = DocumentIngestionMessage.builder()
                        .documentId(documentId)
                        .taskId(taskId)
                        .fileName(fileName)
                        .fileType(fileType)
                        .filePath(mergedFile.toString())
                        .fileSize(fileSize)
                        .createdAt(Instant.now().toEpochMilli())
                        .build();

                boolean sent = ingestionProducer.send(message);
                if (sent) {
                    docInfo.put("status", "ACCEPTED");
                    log.info("Chunk upload complete, sent to RocketMQ: documentId={}, file={}, chunks={}",
                            documentId, fileName, session.totalChunks);
                } else {
                    doDirectIngestion(documentId, fileName, fileType, mergedFile, docInfo);
                }
            } else {
                doDirectIngestion(documentId, fileName, fileType, mergedFile, docInfo);
            }

            statusStore.create(documentId, docInfo);
            chunkUploadManager.removeSession(uploadId);

            Map<String, Object> stored = statusStore.get(documentId);
            return DocumentResponse.builder()
                    .documentId(documentId)
                    .fileName(fileName)
                    .status((String) stored.getOrDefault("status", "ACCEPTED"))
                    .taskId(taskId)
                    .size(fileSize)
                    .createdAt((String) stored.getOrDefault("createdAt", ""))
                    .build();

        } catch (Exception e) {
            log.error("Chunk upload complete failed: uploadId={}: {}", uploadId, e.getMessage(), e);
            chunkUploadManager.removeSession(uploadId);
            return DocumentResponse.builder()
                    .documentId(documentId)
                    .fileName(fileName)
                    .status("FAILED: " + e.getMessage())
                    .taskId(taskId)
                    .build();
        }
    }

    private void doDirectIngestion(String documentId, String fileName, String fileType,
                                      Path filePath, Map<String, Object> docInfo) {
        if (ingestionPipeline == null) {
            docInfo.put("status", "ACCEPTED_PENDING");
            log.warn("Ingestion pipeline not available, document {} pending", documentId);
            return;
        }
        try {
            log.info("Falling back to direct ingestion: documentId={}, file={}", documentId, fileName);
            statusStore.update(documentId, "PROCESSING_DIRECT", null);

            Document document = new Document(documentId, fileName, fileType);
            document.setFileSize(Files.size(filePath));
            document.setUploadedAt(Instant.now());

            document = ingestionPipeline.ingestToEs(document, filePath, status -> {
                statusStore.update(documentId, status.name(), null);
            });

            docInfo.put("status", "READY");
            docInfo.put("chunkCount", document.getChunkCount());
            log.info("Direct ingestion completed: documentId={}, chunks={}", documentId, document.getChunkCount());
        } catch (Exception ex) {
            log.error("Direct ingestion failed: documentId={}", documentId, ex);
            docInfo.put("status", "FAILED: " + ex.getMessage());
        }
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

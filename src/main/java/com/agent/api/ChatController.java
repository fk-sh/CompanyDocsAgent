package com.agent.api;

import com.agent.agent.OrchestratorAgent;
import com.agent.api.dto.ChatRequest;
import com.agent.api.dto.ChatResponse;
import com.agent.api.dto.DocumentResponse;
import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Document;
import com.agent.auth.CurrentUser;
import com.agent.auth.CurrentUserHolder;
import com.agent.document.DocumentEntity;
import com.agent.document.DocumentService;
import com.agent.document.DocumentVisibility;
import com.agent.document.ManagedDocumentStatus;
import com.agent.ingestion.FullIngestionPipeline;
import com.agent.memory.MemoryManager;
import com.agent.mq.DocumentIngestionMessage;
import com.agent.mq.DocumentIngestionProducer;
import com.agent.mq.IngestionStatusStore;
import com.agent.memory.preference.PreferenceMemoryExtractor;
import com.agent.upload.ChunkUploadManager;
import com.agent.vectordb.ElasticsearchVectorStore;
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

    // 存储用户反馈的缓存，键为 sessionId，值为反馈内容
    // 用于记录用户对模型的反馈，比如点赞、评论等
    // 可以根据需要扩展，比如添加时间戳、用户ID等
    // 这里简单起见，只记录反馈内容
    private final Map<String, Map<String, Object>> feedbackStore = new LinkedHashMap<>();

    @Autowired(required = false)
    private DocumentIngestionProducer ingestionProducer;

    @Autowired
    private IngestionStatusStore statusStore;

    @Autowired
    private ChunkUploadManager chunkUploadManager;

    @Autowired(required = false)
    private FullIngestionPipeline ingestionPipeline;

    @Autowired
    private DocumentService documentService;

    @Autowired(required = false)
    private ElasticsearchVectorStore vectorStore;

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

        memoryManager.saveUserMessage(sessionId, query);// 保存用户消息到内存管理器

        String userId = memoryManager.resolveUserId(sessionId);// 从内存管理器中获取用户ID
        preferenceExtractor.extractAndStoreAsync(userId, query);// 异步提取用户偏好并存储到内存管理器

        long start = System.currentTimeMillis();
        AgentContext ctx = memoryManager.buildContext(sessionId, query);// 构建上下文，包含用户消息和偏好
        enrichCurrentUserContext(ctx);
        String answer = orchestratorAgent.execute(ctx);// 执行orchestratorAgent，获取模型回答

        long latencyMs = System.currentTimeMillis() - start;// 计算请求处理耗时，单位毫秒

        List<String> contexts = extractContexts(ctx);

        memoryManager.saveAssistantMessage(sessionId, answer);// 保存模型回答到内存管理器

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

        memoryManager.saveUserMessage(sid, query);//保存当前信息到移动窗口和MySQL 表

        String uid = memoryManager.resolveUserId(sid);// 从内存管理器中获取用户ID
        preferenceExtractor.extractAndStoreAsync(uid, query);// 异步提取用户偏好并存储到内存管理器

        AgentContext ctx = memoryManager.buildContext(sid, query);
        enrichCurrentUserContext(ctx);

        StringBuilder fullAnswer = new StringBuilder();
        String finalSid = sid;

        return orchestratorAgent.executeStream(ctx)// 执行orchestratorAgent，获取模型回答流
                .doOnNext(fullAnswer::append)// 累加每个模型回答片段到 StringBuilder
                .doOnComplete(() -> {
                    String answer = fullAnswer.toString();// 从 StringBuilder 中获取完整的模型回答
                    if (!answer.isEmpty()) {
                        memoryManager.saveAssistantMessage(finalSid, answer);// 保存模型回答到内存管理器
                    }
                    memoryManager.updateSessionTitle(finalSid,
                            query.length() > 30 ? query.substring(0, 30) + "\u2026" : query);// 更新会话标题
                    log.info("Stream completed for session={}", finalSid);
                })
                .doOnError(e -> log.error("Stream error for session={}: {}", finalSid, e.getMessage()));
    }


    // ======================== 文档上传 ========================
    @PostMapping("/documents/upload")
    public DocumentResponse uploadDocument(@RequestParam("file") MultipartFile file,
                                           @RequestParam(defaultValue = "DEPARTMENT") String visibility) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);// 生成唯一的任务ID
        String fileName = file.getOriginalFilename();
        CurrentUser user = CurrentUserHolder.require();// 获取当前用户，如果不存在则抛出异常
        DocumentVisibility documentVisibility = parseVisibility(visibility);// 解析文档可见性参数（部门或全公司）

        log.info("POST /documents/upload fileName={}, size={}, taskId={}", fileName, file.getSize(), taskId);

        String documentId = UUID.randomUUID().toString().replace("-", "");// 生成唯一的文档ID
        
        // 上传文件到本地的临时目录，后续的发送给MQ的消息其实都是上传的临时路径，而并不是文件本身
        try {
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "agent-uploads");// 创建临时目录
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
            }
            Files.createDirectories(tempDir);
            Path tempFile = tempDir.resolve(taskId + "_" + fileName);
            file.transferTo(tempFile.toFile());

            String fileType = fileName != null && fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                    : "unknown";

            // 记录文档基本信息
            Map<String, Object> docInfo = new LinkedHashMap<>();
            docInfo.put("documentId", documentId);
            docInfo.put("fileName", fileName);
            docInfo.put("size", file.getSize());
            docInfo.put("taskId", taskId);
            docInfo.put("fileType", fileType);
            docInfo.put("uploaderName", user.getName());
            docInfo.put("department", user.getDepartment());
            docInfo.put("visibility", documentVisibility.name());
            docInfo.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

            if (ingestionProducer != null) {// 如果有RocketMQ生产者
                DocumentIngestionMessage message = DocumentIngestionMessage.builder()
                        .documentId(documentId)
                        .taskId(taskId)
                        .fileName(fileName)
                        .fileType(fileType)
                        .filePath(tempFile.toString())
                        .fileSize(file.getSize())
                        .createdAt(Instant.now().toEpochMilli())
                        .uploaderName(user.getName())
                        .department(user.getDepartment())
                        .visibility(documentVisibility.name())
                        .build();

                boolean sent = ingestionProducer.send(message);// 发送文档信息到RocketMQ
                if (sent) {
                    docInfo.put("status", "ACCEPTED");
                    log.info("Document {} sent to RocketMQ: {}", documentId, fileName);
                } else {// 发送失败，直接进行直接导入
                    doDirectIngestion(documentId, fileName, fileType, tempFile, docInfo);
                }
            } else {// 没有RocketMQ生产者，直接进行直接导入
                doDirectIngestion(documentId, fileName, fileType, tempFile, docInfo);
            }

            statusStore.create(documentId, docInfo);// 记录文档基本信息
            documentService.createProcessing(documentId, taskId, fileName, fileType,
                    file.getSize(), tempFile.toString(), user, documentVisibility);// 创建处理中的文档实体
            syncDocumentStatus(documentId, (String) docInfo.get("status"), docInfo.get("chunkCount"));// 同步文档状态
        

        } catch (Exception e) {// 捕获异常, 并记录错误日志, 并更新文档状态为失败
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

        Map<String, Object> docInfo = statusStore.get(documentId);// 获取文档基本信息
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

    // 列表并返回所有文档,
    @GetMapping("/documents")
    public List<DocumentResponse> listDocuments() {
        log.info("GET /documents, count={}", statusStore.size());
        return statusStore.all().stream()// 流处理所有文档基本信息
                .map(doc -> DocumentResponse.builder()
                        .documentId((String) doc.get("documentId"))
                        .fileName((String) doc.get("fileName"))
                        .status((String) doc.getOrDefault("status", "UNKNOWN"))
                        .taskId((String) doc.get("taskId"))
                        .size(doc.get("size") instanceof Long s ? s : 0L)
                        .createdAt((String) doc.get("createdAt"))
                        .build())
                .toList();// 转换为列表
    }

    // 列表并返回用户自己的文档, 分页查询
    // @param limit 每页数量
    // @param offset 偏移量
    @GetMapping("/documents/mine")
    public List<DocumentResponse> listMyDocuments(@RequestParam(defaultValue = "100") int limit,
                                                  @RequestParam(defaultValue = "0") int offset) {
        CurrentUser user = CurrentUserHolder.require();
        log.info("GET /documents/mine userId={}, limit={}, offset={}", user.getId(), limit, offset);
        return documentService.listMine(user.getId(), limit, offset).stream()
                .map(this::refreshAndToDocumentResponse)
                .toList();
    }

    // 获取文档详情
    // @param id 文档ID
        // @param id 文档ID
        // @return 文档响应对象
        //通过文档ID获取文档基本信息
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

    // 列表并返回所有失败的文档
    // @return 文档响应对象列表
    //通过文档状态获取所有失败的文档
    @GetMapping("/documents/failed")
    public List<DocumentResponse> listDeadLettered() {
        List<Map<String, Object>> deadLettered = statusStore.findDeadLettered();
        log.info("GET /documents/failed, count={}", deadLettered.size());
        return deadLettered.stream()// 流处理所有失败文档基本信息
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

    // 重试失败的文档
    // @param id 文档ID
    // @return 重试结果
    //通过文档ID重试失败的文档
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

    // 初始化分块上传
    // @param body 文件信息
    // @return 分块上传初始化结果
    //通过文件信息初始化分块上传会话
    //它不是实际上传文件内容的接口，而是 上传大文件之前的准备接口 。
    //因为分片上传不是一次性上传完整文件。他会将文件分成多个分块，每个分块上传到服务器。实现断点续传。
  //  因此后端需要先知道：
// 这个文件叫什么
// 总大小多少
// 一共有多少片
// 每片多大
// 文件 hash 是什么等这些信息
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
                    fileHash, fileName, fileSize, totalChunks, chunkSize);// 初始化分块上传会话

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

    // 上传分块
    // @param uploadId 上传ID
    // @param chunkIndex 分块索引
    // @param file 分块文件
    // @return 分块上传结果
    //通过上传ID和分块索引上传分块文件
    @PostMapping("/documents/chunk/{uploadId}")
    public Map<String, Object> uploadChunk(@PathVariable String uploadId,
                                           @RequestParam("chunkIndex") int chunkIndex,
                                           @RequestParam("file") MultipartFile file) {
        try {
            byte[] data = file.getBytes();// 读取分块文件内容
            chunkUploadManager.writeChunk(uploadId, chunkIndex, data);// 写入分块文件
            Map<String, Object> progress = chunkUploadManager.getProgress(uploadId);// 获取上传进度
            log.debug("Chunk uploaded: uploadId={}, chunk={}", uploadId, chunkIndex);// 记录上传日志
            return progress;// 返回上传进度
        } catch (Exception e) {
            log.error("Failed to upload chunk: uploadId={}, chunk={}: {}", uploadId, chunkIndex, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    // 获取分块上传进度
    // @param uploadId 上传ID
    // @return 分块上传进度
    //通过上传ID获取分块上传进度
    @GetMapping("/documents/chunk/{uploadId}/progress")
    public Map<String, Object> chunkProgress(@PathVariable String uploadId) {
        return chunkUploadManager.getProgress(uploadId);
    }

    // 完成分块上传
    // @param uploadId 上传ID
    // @param visibility 文可见性
    // @return 完成上传结果
    //通过上传ID完成分块上传
    @PostMapping("/documents/chunk/{uploadId}/complete")
    public DocumentResponse completeChunkUpload(@PathVariable String uploadId,
                                                @RequestParam(defaultValue = "DEPARTMENT") String visibility) {
        ChunkUploadManager.UploadSession session = chunkUploadManager.getSession(uploadId);//根据 uploadId 查找会话
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
        CurrentUser user = CurrentUserHolder.require();
        DocumentVisibility documentVisibility = parseVisibility(visibility);

        try {
            chunkUploadManager.complete(uploadId);// 完成分块上传会话

            Path mergedFile = session.tempFile;// 获取合并后的文件路径
            long fileSize = Files.size(mergedFile);// 获取合并后的文件大小

            String fileType = fileName.contains(".")
                    ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                    : "unknown";

            Map<String, Object> docInfo = new LinkedHashMap<>();
            docInfo.put("documentId", documentId);
            docInfo.put("fileName", fileName);
            docInfo.put("size", fileSize);
            docInfo.put("taskId", taskId);
            docInfo.put("fileType", fileType);
            docInfo.put("uploaderName", user.getName());
            docInfo.put("department", user.getDepartment());
            docInfo.put("visibility", documentVisibility.name());
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
                        .uploaderName(user.getName())
                        .department(user.getDepartment())
                        .visibility(documentVisibility.name())
                        .build();

                boolean sent = ingestionProducer.send(message);// 发送文档消息到 RocketMQ
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

            statusStore.create(documentId, docInfo);// 创建文档状态记录
            documentService.createProcessing(documentId, taskId, fileName, fileType,
                    fileSize, mergedFile.toString(), user, documentVisibility);// 创建文档处理记录
            syncDocumentStatus(documentId, (String) docInfo.get("status"), docInfo.get("chunkCount"));// 同步文档状态
            chunkUploadManager.removeSession(uploadId);// 移除上传会话  

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

    private DocumentVisibility parseVisibility(String visibility) {// 解析文档可见性参数
        try {
            return DocumentVisibility.valueOf(visibility);
        } catch (Exception e) {
            return DocumentVisibility.DEPARTMENT;
        }
    }

    // 同步文档状态
    private void syncDocumentStatus(String documentId, String status, Object chunkCount) {
        ManagedDocumentStatus managedStatus = switch (status == null ? "" : status) {
            case "READY" -> ManagedDocumentStatus.READY;// 文档已就绪
            case "FAILED", "DEAD_LETTERED" -> ManagedDocumentStatus.FAILED;// 文档处理失败
            default -> ManagedDocumentStatus.PROCESSING;// 文档处理中
        };
        Integer count = chunkCount instanceof Number n ? n.intValue() : null;// 转换为整数或null
        documentService.updateStatus(documentId, managedStatus, count);// 更新文档状态
    }

    // 刷新并转换为响应
    private DocumentResponse refreshAndToDocumentResponse(DocumentEntity doc) {
        if (ManagedDocumentStatus.PROCESSING.name().equals(doc.getStatus()) && vectorStore != null) {
            long chunkCount = vectorStore.countByDocumentId(doc.getId());
            if (chunkCount > 0) {
                documentService.updateStatus(doc.getId(), ManagedDocumentStatus.READY, Math.toIntExact(chunkCount));
                doc.setStatus(ManagedDocumentStatus.READY.name());
                doc.setChunkCount(Math.toIntExact(chunkCount));
            }
        }
        return toDocumentResponse(doc);
    }

    private DocumentResponse toDocumentResponse(DocumentEntity doc) {
        return DocumentResponse.builder()
                .documentId(doc.getId())
                .fileName(doc.getFileName())
                .status(doc.getStatus())
                .taskId(doc.getTaskId())
                .size(doc.getFileSize() == null ? 0L : doc.getFileSize())
                .createdAt(doc.getCreatedAt() == null ? "" : doc.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .uploaderId(doc.getUploaderId())
                .uploaderName(doc.getUploaderName())
                .department(doc.getDepartment())
                .visibility(doc.getVisibility())
                .chunkCount(doc.getChunkCount())
                .build();
    }

    // 直接上传文档，在没有RocketMQ时使用
    private void doDirectIngestion(String documentId, String fileName, String fileType,
                                      Path filePath, Map<String, Object> docInfo) {
        if (ingestionPipeline == null) {
            docInfo.put("status", "ACCEPTED_PENDING");// 文档已接受，但处理中
            log.warn("Ingestion pipeline not available, document {} pending", documentId);
            return;
        }
        try {
            log.info("Falling back to direct ingestion: documentId={}, file={}", documentId, fileName);
            statusStore.update(documentId, "PROCESSING_DIRECT", null);

            Document document = new Document(documentId, fileName, fileType);// 创建文档实体
            document.setFileSize(Files.size(filePath));// 设置文件大小
            document.setUploadedAt(Instant.now());
            copyDocumentMetadata(document, docInfo);

            // 直接上传文档到 Elasticsearch
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

    private void copyDocumentMetadata(Document document, Map<String, Object> docInfo) {
        Object uploaderName = docInfo.get("uploaderName");
        Object department = docInfo.get("department");
        Object visibility = docInfo.get("visibility");
        if (uploaderName != null) document.addMetadata("uploaderName", uploaderName);
        if (department != null) document.addMetadata("department", department);
        document.addMetadata("visibility", visibility != null ? visibility : DocumentVisibility.COMPANY.name());
    }

    private void enrichCurrentUserContext(AgentContext ctx) {
        CurrentUser user = CurrentUserHolder.get();
        if (user == null) {
            return;
        }
        ctx.setVariable("userId", user.getId());
        ctx.setVariable("department", user.getDepartment() != null ? user.getDepartment() : "");
        ctx.setVariable("role", user.getRole() != null ? user.getRole() : "USER");
    }

    // 从上下文提取上下文段
    private List<String> extractContexts(AgentContext ctx) {
        List<String> contexts = new ArrayList<>();
        String retrievedContext = ctx.getVariable("retrievedContext", "");// 从上下文获取检索到的上下文
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

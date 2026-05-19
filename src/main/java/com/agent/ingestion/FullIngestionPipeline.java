package com.agent.ingestion;

import com.agent.core.Chunk;
import com.agent.core.Document;
import com.agent.core.EmbeddingService;
import com.agent.vectordb.ElasticsearchVectorStore;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 全链路文档摄入管道，串联 Phase 4（解析→切割）和 Phase 5（向量化→ES 存储）。
 * <p>
 * 管道流程：
 * <pre>
 *   Document(UPLOADED)
 *     │
 *     ├─(1) IngestionService.ingest()
 *     │    ├── DocumentParser.parse()        解析 PDF/Word/MD → 原始文本
 *     │    ├── ContentExtractor.extract()    提取 TEXT/TABLE/CODE/IMAGE
 *     │    └── ParentChildChunker.chunk()    父子切割 → List&lt;Chunk&gt;
 *     │
 *     ├─(2) EmbeddingService.embedBatch()
 *     │    ├── EmbeddingCache.get()          检查 Redis 缓存命中
 *     │    └── POST /v1/embeddings          调用 bge-large-zh-v1.5
 *     │
 *     └─(3) ElasticsearchVectorStore.upsertBatch()
 *          └── ES Bulk API                  agent_chunks 索引
 * </pre>
 * <p>
 * Bean 由 {@link com.agent.vectordb.VectorDbAutoConfiguration} 条件装配创建，
 * 仅在 ES 可用时激活。
 */
@Slf4j
public class FullIngestionPipeline {

    private final IngestionService ingestionService;
    private final EmbeddingService embeddingService;
    private final ElasticsearchVectorStore vectorStore;

    public FullIngestionPipeline(IngestionService ingestionService,
                                 EmbeddingService embeddingService,
                                 ElasticsearchVectorStore vectorStore) {
        this.ingestionService = ingestionService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /**
     * 从文件路径自动创建 Document 并执行全链路摄入。
     * Document ID 由 UUID 生成，fileType 从文件扩展名推断。
     *
     * @param filePath 文档路径
     * @return 摄入完成后的 Document（状态为 READY）
     */
    public Document ingestToEs(Path filePath) throws IOException {
        String fileName = filePath.getFileName().toString();
        String fileType = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        Document document = new Document(UUID.randomUUID().toString(), fileName, fileType);

        return ingestToEs(document, filePath, null);
    }

    /**
     * 全链路摄入（带状态回调），状态机流转：UPLOADED → EMBEDDING → INDEXING → READY。
     *
     * @param document       文档模型
     * @param filePath       文件路径
     * @param statusCallback 状态变更回调（可为 null）
     * @return 摄入完成后的 Document（状态为 READY）
     */
    public Document ingestToEs(Document document, Path filePath,
                                Consumer<Document.DocumentStatus> statusCallback) throws IOException {
        log.info("Starting full ingestion pipeline for document: {}", document.getId());

        List<Chunk> chunks = ingestionService.ingest(document, filePath, statusCallback);

        updateStatus(document, Document.DocumentStatus.EMBEDDING, statusCallback);
        List<String> texts = chunks.stream().map(Chunk::getContent).toList();
        List<float[]> embeddings = embeddingService.embedBatch(texts);

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(embeddings.get(i));
        }
        log.info("Generated {} embeddings for document {}", embeddings.size(), document.getId());

        updateStatus(document, Document.DocumentStatus.INDEXING, statusCallback);
        vectorStore.upsertBatch(chunks);
        document.setChunkCount(chunks.size());
        log.info("Indexed {} chunks to ES for document {}", chunks.size(), document.getId());

        updateStatus(document, Document.DocumentStatus.READY, statusCallback);
        return document;
    }

    /**
     * 全链路摄入（无状态回调）。
     */
    public Document ingestToEs(Document document, Path filePath) throws IOException {
        return ingestToEs(document, filePath, null);
    }

    /**
     * 从 ES 中删除指定文档的所有 Chunk。
     */
    public void deleteDocument(String documentId) {
        vectorStore.deleteByDocumentId(documentId);
        log.info("Deleted document {} from ES", documentId);
    }

    /**
     * 查询某文档在 ES 中的 Chunk 数。
     */
    public long getDocumentChunkCount(String documentId) {
        return vectorStore.countByDocumentId(documentId);
    }

    private void updateStatus(Document document, Document.DocumentStatus status,
                               Consumer<Document.DocumentStatus> callback) {
        document.setStatus(status);
        if (callback != null) {
            callback.accept(status);
        }
    }
}

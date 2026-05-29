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
        // 从文件扩展名推断文件类型
        String fileType = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
        // 创建 Document 实例
        log.info("Creating document for file: {} with type: {}", fileName, fileType);
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

    //这个就是全链路摄入管道的主方法，负责调用其他组件完成文档摄入任务
    public Document ingestToEs(Document document, Path filePath,
                                Consumer<Document.DocumentStatus> statusCallback) throws IOException {
        log.info("Starting full ingestion pipeline for document: {}", document.getId());
        // 调用 IngestionService 进行文档解析、切割和向量化，返回的是一个 列表，每个元素是一个 Chunk
        // 每个 Chunk 包含原始文本、解析后的文本、向量等信息，但是此时还没有向量化，因此向量值为空
        //test_doc.md → PDF/Word/MD 解析器 → 提取 TEXT/Table/Code → ParentChildChunker 切割 → 16 个 Chunk
        List<Chunk> chunks = ingestionService.ingest(document, filePath, statusCallback);

        String fileName = document.getFileName();
        for (Chunk chunk : chunks) {
            chunk.addMetadata("fileName", fileName);
        }

        updateStatus(document, Document.DocumentStatus.EMBEDDING, statusCallback);
        // 从 Chunk 中提取文本内容
        List<String> texts = chunks.stream().map(Chunk::getContent).toList();
        // 调用 EmbeddingService 生成向量
        // 每个向量的维度是 1024，每个元素是一个 float 值
        List<float[]> embeddings = embeddingService.embedBatch(texts);

        // 遍历每个 Chunk，将向量赋值给对应的 Chunk
        // 每个 Chunk 包含原始文本、解析后的文本、向量等信息
        // 每个向量的维度是 1024，每个元素是一个 float 值
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(embeddings.get(i));
        }
        log.info("Generated {} embeddings for document {}", embeddings.size(), document.getId());
        // 把文档状态从 EMBEDDING 改为 INDEXING
        updateStatus(document, Document.DocumentStatus.INDEXING, statusCallback);
        // 调用 ElasticsearchVectorStore 将 Chunk 写入 ES
        // 每个 Chunk 包含原始文本、解析后的文本、向量等信息
        // 每个向量的维度是 1024，每个元素是一个 float 值
        vectorStore.upsertBatch(chunks);
        document.setChunkCount(chunks.size());// 设置文档的 Chunk 数
        log.info("Indexed {} chunks to ES for document {}", chunks.size(), document.getId());
        // 把文档状态从 INDEXING 改为 READY，表示文档已完全摄入，该流程已结束
        updateStatus(document, Document.DocumentStatus.READY, statusCallback);
        return document;
    }

    /**
     * 全链路摄入（无状态回调）。没有状态变更回调，直接进行文档向量化和写入 ES。
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

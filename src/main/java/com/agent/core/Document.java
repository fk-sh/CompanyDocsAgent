package com.agent.core;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档模型，表示一份上传到知识库的原始文档。
 * <p>
 * 文档经过摄入管道（Ingestion Pipeline）处理后，被拆分为多个 {@link Chunk}，
 * 最终向量化存入 ES。状态机追踪文档处理进度：
 * <pre>
 *   UPLOADED → PARSING → CHUNKING → EMBEDDING → INDEXING → READY
 *                                                          ↘ FAILED
 * </pre>
 */
@Getter
@Setter
public class Document {

    /** 文档处理状态枚举 */
    public enum DocumentStatus {
        /** 已上传，等待处理 */
        UPLOADED,
        /** 解析中 */
        PARSING,
        /** 切割中 */
        CHUNKING,
        /** 向量化中 */
        EMBEDDING,
        /** 写入 ES 中 */
        INDEXING,
        /** 就绪，可被检索 */
        READY,
        /** 处理失败 */
        FAILED
    }

    /** 文档唯一标识 */
    private String id;

    /** 文档标题（从内容自动提取或用户指定） */
    private String title;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型（pdf / docx / md 等） */
    private String fileType;

    /** 文件存储路径 */
    private String filePath;

    /** 文件大小（字节） */
    private long fileSize;

    /** 上传时间 */
    private Instant uploadedAt;

    /** 当前处理状态 */
    private DocumentStatus status;

    /** 切割后的 Chunk 数量 */
    private int chunkCount;

    /** 自定义元数据（如作者、部门、标签） */
    private final Map<String, Object> metadata = new HashMap<>();

    public Document() {
    }

    public Document(String id, String fileName, String fileType) {
        this.id = id;
        this.fileName = fileName;
        this.fileType = fileType;
        this.status = DocumentStatus.UPLOADED;
        this.uploadedAt = Instant.now();
    }

    /** 添加自定义元数据 */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
}

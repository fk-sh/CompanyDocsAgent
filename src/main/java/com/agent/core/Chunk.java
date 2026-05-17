package com.agent.core;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档分块模型，文档切割后的最小检索单元。
 * <p>
 * 核心设计：
 * <ul>
 *   <li><b>多模态内容</b>：TEXT / IMAGE_DESCRIPTION / TABLE / CODE 四种类型</li>
 *   <li><b>父子 Chunk</b>：子 Chunk（~512 字符）用于向量检索，父 Chunk（~2048 字符）送入 LLM</li>
 *   <li><b>重叠窗口</b>：相邻 Chunk 之间有重叠，防止关键信息被切在边界</li>
 *   <li><b>向量字段</b>：embedding 由 bge-large-zh-v1.5 生成，1024 维 float 数组</li>
 * </ul>
 * <p>
 * 关系：Document 1:N Chunk，子 Chunk 通过 parentChunkId 指向父 Chunk。
 */
@Getter
@Setter
public class Chunk {

    /** Chunk 内容类型枚举 */
    public enum ContentType {
        /** 纯文本段落 */
        TEXT,
        /** VLM 生成的图片文字描述 */
        IMAGE_DESCRIPTION,
        /** 结构化表格（Markdown 格式） */
        TABLE,
        /** 代码块 */
        CODE
    }

    /** Chunk 唯一标识 */
    private String id;

    /** 所属文档 ID */
    private String documentId;

    /** 父 Chunk ID（子 Chunk 命中时自动拉取父 Chunk 完整上下文） */
    private String parentChunkId;

    /** 内容类型 */
    private ContentType contentType;

    /** Chunk 文本内容 */
    private String content;

    /** 在文档中的序号 */
    private int chunkIndex;

    /** 在原文档中的起始偏移（字符位置） */
    private int startOffset;

    /** 在原文档中的结束偏移（字符位置） */
    private int endOffset;

    /** BGE 模型生成的 1024 维向量 */
    private float[] embedding;

    /** 自定义元数据 */
    private final Map<String, Object> metadata = new HashMap<>();

    /** 创建时间 */
    private Instant createdAt;

    public Chunk() {
    }

    public Chunk(String id, String documentId, String content, ContentType contentType, int chunkIndex) {
        this.id = id;
        this.documentId = documentId;
        this.content = content;
        this.contentType = contentType;
        this.chunkIndex = chunkIndex;
        this.createdAt = Instant.now();
    }

    /**
     * @return true 表示当前 Chunk 有父 Chunk
     */
    public boolean hasParent() {
        return parentChunkId != null && !parentChunkId.isBlank();
    }

    /**
     * @return 向量维度，未向量化时返回 0
     */
    public int embeddingDimension() {
        return embedding != null ? embedding.length : 0;
    }

    /** 添加自定义元数据 */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @Override
    public String toString() {
        return "Chunk{" +
                "id='" + id + '\'' +
                ", documentId='" + documentId + '\'' +
                ", contentType=" + contentType +
                ", chunkIndex=" + chunkIndex +
                ", embeddingDim=" + embeddingDimension() +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Chunk chunk)) return false;
        return id != null && id.equals(chunk.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}

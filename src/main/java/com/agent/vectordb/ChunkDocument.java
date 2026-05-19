package com.agent.vectordb;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * ES 索引文档 POJO，用于 Elasticsearch Java Client 的 JSON 序列化/反序列化。
 * <p>
 * 字段与 {@code agent_chunks} 索引的 mapping 一一对应。
 * embedding 字段为 {@code List<Float>}，对应 ES 的 {@code dense_vector(1024)} 类型。
 * <p>
 * 包级私有，仅供 {@link ElasticsearchVectorStore} 内部使用。
 */
@Getter
@Setter
class ChunkDocument {

    /** Chunk 唯一标识，对应 ES 文档 _id */
    private String id;

    /** 所属文档 ID，用于按文档删除/统计 */
    private String documentId;

    /** 父 Chunk ID，null 表示本身即为父 Chunk */
    private String parentChunkId;

    /** 内容类型：TEXT / TABLE / CODE / IMAGE */
    private String contentType;

    /** Chunk 原始文本内容 */
    private String content;

    /** 在文档内的切片序号，从 0 开始 */
    private int chunkIndex;

    /** 在原始文本中的起始偏移量（字符） */
    private int startOffset;

    /** 在原始文本中的结束偏移量（字符） */
    private int endOffset;

    /** 1024 维向量，对应 ES dense_vector 类型 */
    private List<Float> embedding;

    /** 额外元数据，ES 检索后 _score 也会注入此处 */
    private Map<String, Object> metadata;

    /** 创建时间，ISO-8601 格式字符串 */
    private String createdAt;
}

package com.agent.core;

import java.util.List;
import java.util.Map;

/**
 * 检索器接口，负责根据查询从知识库中召回相关文档 Chunk。
 * <p>
 * 典型实现为混合检索（HybridRetriever），在 ES 中一条查询完成：
 * BM25 全文 + KNN 向量 + 元数据过滤 + RRF 融合。
 * <p>
 * 检索结果经粗排→精排后返回最终 TopK 文档列表。
 *
 * @see RecallStrategy
 * @see Reranker
 */
public interface Retriever {

    /**
     * 检索并返回 TopK 文档 Chunk。
     *
     * @param query 用户查询
     * @param topK  返回条数
     * @return 排序后的 Chunk 列表
     */
    List<Chunk> retrieve(String query, int topK);

    /**
     * 带元数据过滤的检索。
     *
     * @param query   用户查询
     * @param topK    返回条数
     * @param filters 元数据过滤条件（如文档类型、时间范围）
     * @return 过滤后的 Chunk 列表
     */
    List<Chunk> retrieve(String query, int topK, Map<String, Object> filters);

    /**
     * 检索 Top10 文档 Chunk（便捷方法）。
     */
    default List<Chunk> retrieve(String query) {
        return retrieve(query, 10);
    }
}

package com.agent.core;

import java.util.List;

/**
 * 召回策略接口，多路召回中的单一通道。
 * <p>
 * 三种典型实现：
 * <ul>
 *   <li><b>VectorRecallStrategy</b>：KNN 向量相似度召回</li>
 *   <li><b>KeywordRecallStrategy</b>：BM25 全文关键词召回</li>
 *   <li><b>MetadataRecallStrategy</b>：元数据精确匹配召回</li>
 * </ul>
 * 多路结果通过 RRF（Reciprocal Rank Fusion）融合后送入粗排。
 */
public interface RecallStrategy {

    /**
     * @return 召回策略名称
     */
    String name();

    /**
     * 执行单路召回。
     *
     * @param query 查询文本
     * @param topK  召回数量
     * @return 召回的 Chunk 列表
     */
    List<Chunk> recall(String query, int topK);

    /**
     * 召回 Top30（便捷方法）。
     */
    default List<Chunk> recall(String query) {
        return recall(query, 30);
    }
}

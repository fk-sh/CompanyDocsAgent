package com.agent.core;

import java.util.List;

/**
 * 精排接口，对候选 Chunk 做 Cross-Encoder 精确相关性打分。
 * <p>
 * 在 HybridRetriever 流程中位置：
 * <pre>
 *   多路召回(RRF 融合) → Top100 候选 → 粗排 → Top30 → 精排(Reranker) → Top5~10
 * </pre>
 * 使用 bge-reranker-v2-m3（Cross-Encoder）模型，对 (query, chunk) 对做精确打分，
 * 弥补向量检索和 BM25 在语义精确匹配上的不足。
 */
public interface Reranker {

    /**
     * 对候选 Chunk 列表做精确重排序。
     *
     * @param query      用户查询
     * @param candidates 粗排后的候选 Chunk 列表
     * @param topK       返回 TopK 条
     * @return 精排后的结果列表，按 score 降序
     */
    List<RerankResult> rerank(String query, List<Chunk> candidates, int topK);

    /**
     * 精排 Top10（便捷方法）。
     */
    default List<RerankResult> rerank(String query, List<Chunk> candidates) {
        return rerank(query, candidates, 10);
    }

    /**
     * 精排结果记录。
     *
     * @param chunk        对应的 Chunk
     * @param score        Cross-Encoder 相关性得分
     * @param originalRank 粗排阶段的原始排名
     */
    record RerankResult(Chunk chunk, double score, int originalRank) {
    }
}

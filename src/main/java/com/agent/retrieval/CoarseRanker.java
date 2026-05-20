package com.agent.retrieval;

import com.agent.core.Chunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class CoarseRanker {

    private static final double BM25_WEIGHT = 0.3;// BM25 权重
    private static final double VECTOR_WEIGHT = 0.5;// 向量权重
    private static final double RRF_WEIGHT = 0.2;// RRF 权重

    /**
     * 对候选文档进行粗粒度排序。
     * 
     * @param query 查询字符串
     * @param candidates 候选文档列表
     * @param topK 要返回的文档数量
     * @return 排序后的文档列表
     */
    public List<RankedChunk> rank(String query, List<RankedChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 为每个候选文档计算粗粒度分数
        for (RankedChunk candidate : candidates) {
            double coarseScore = computeCoarseScore(candidate);
            candidate.setCoarseScore(coarseScore);
        }

        // 复制候选文档列表，避免修改原始列表
        List<RankedChunk> ranked = new ArrayList<>(candidates);
        
        // 按粗粒度分数降序排序
        ranked.sort(Comparator.comparingDouble(r -> -r.coarseScore()));

        // 取前 topK 个文档
        int resultSize = Math.min(topK, ranked.size());  
        // 为每个文档设置粗粒度排名
        List<RankedChunk> topResults = ranked.subList(0, resultSize);

        //排好名后给每一个文档设置粗粒度排名
        for (int i = 0; i < topResults.size(); i++) {
            // 设置粗粒度排名
            topResults.get(i).setCoarseRank(i + 1);
            
        }

        log.debug("Coarse ranker: {} -> {} candidates", candidates.size(), topResults.size());
        return topResults;
    }

    // 计算粗粒度分数
    public static double computeCoarseScore(RankedChunk candidate) {
        Map<String, Object> metadata = candidate.chunk().getMetadata();
        double bm25Score = toDouble(metadata.get("bm25_score"));
        double knnScore = toDouble(metadata.get("knn_score"));
        double rrfScore = toDouble(metadata.get("rrf_score"));

        // 计算粗粒度分数并返回
        return BM25_WEIGHT * bm25Score
                + VECTOR_WEIGHT * knnScore
                + RRF_WEIGHT * rrfScore;
    }

    // 将对象转换为 double 类型
    private static double toDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }
}

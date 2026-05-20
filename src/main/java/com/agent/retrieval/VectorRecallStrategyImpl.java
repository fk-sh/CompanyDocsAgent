package com.agent.retrieval;

import com.agent.core.Chunk;
import com.agent.core.EmbeddingService;
import com.agent.core.RecallStrategy;
import com.agent.vectordb.ElasticsearchVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component("vectorRecall")
// 使用向量进行向量召回策略实现类
public class VectorRecallStrategyImpl implements RecallStrategy {

    private final ElasticsearchVectorStore vectorStore;
    private final EmbeddingService embeddingService;

    public VectorRecallStrategyImpl(ElasticsearchVectorStore vectorStore, EmbeddingService embeddingService) {
        this.vectorStore = vectorStore;
        this.embeddingService = embeddingService;
    }

    @Override
    public String name() {
        return "vector_knn";
    }

    @Override
    // 实现向量召回策略，根据查询文本返回 Top-K 个相似的 Chunk
    public List<Chunk> recall(String query, int topK) {
        float[] queryVector = embeddingService.embed(query);
        List<Chunk> results = vectorStore.knnSearch(queryVector, topK);
        log.debug("Vector recall [{}] returned {} chunks for query: {}", name(), results.size(), query);
        return results;
    }
}

package com.agent.retrieval;

import com.agent.core.Chunk;
import com.agent.core.RecallStrategy;
import com.agent.vectordb.ElasticsearchVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component("keywordRecall")
// BM25 关键词召回策略实现
public class KeywordRecallStrategyImpl implements RecallStrategy {

    private final ElasticsearchVectorStore vectorStore;

    public KeywordRecallStrategyImpl(ElasticsearchVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public String name() {
        return "keyword_bm25";
    }

    @Override
    public List<Chunk> recall(String query, int topK) {
        return recall(query, topK, Map.of());
    }

    @Override
    public List<Chunk> recall(String query, int topK, Map<String, Object> filters) {
        List<Chunk> results = vectorStore.bm25Search(query, topK, filters);
        log.info("Keyword recall [{}] returned {} chunks for query: {}", name(), results.size(), query);
        return results;
    }
}

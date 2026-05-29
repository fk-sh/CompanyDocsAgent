package com.agent.memory.preference;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import com.agent.core.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class PreferenceMemoryStore {

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;

    public PreferenceMemoryStore(ElasticsearchClient esClient, EmbeddingService embeddingService) {
        this.esClient = esClient;
        this.embeddingService = embeddingService;
    }

    /**
     * 单条写入偏好（全新插入）。
     */
    public void insert(String userId, PreferenceItem item) {
        try {
            float[] vector = embeddingService.embed(item.content);
            String id = UUID.randomUUID().toString().replace("-", "");

            PreferenceDocument doc = new PreferenceDocument(
                    id, userId, item.content, item.category, item.key, item.value,
                    toFloatList(vector), Instant.now().toString(), Instant.now().toString()
            );

            esClient.index(i -> i
                    .index(PreferenceMemoryIndexInitializer.PREFERENCES_INDEX)
                    .id(id)
                    .document(doc)
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));

            log.info("Stored preference: id={}, userId={}, key={}, value={}", id, userId, item.key, item.value);
        } catch (IOException e) {
            log.error("Failed to store preference: userId={}, key={}", userId, item.key, e);
        }
    }

    /**
     * 按 key 和 userId 查询已有偏好，用于判断累积/覆盖。
     */
    public List<PreferenceDocument> findByKey(String userId, String key) {
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(PreferenceMemoryIndexInitializer.PREFERENCES_INDEX)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("userId").value(userId)))
                            .must(m -> m.term(t -> t.field("key").value(key)))
                    ))
                    .size(1)
            );

            List<Hit<PreferenceDocument>> hits = esClient.search(request, PreferenceDocument.class)
                    .hits().hits();

            List<PreferenceDocument> results = new ArrayList<>();
            for (Hit<PreferenceDocument> hit : hits) {
                if (hit.source() != null) {
                    results.add(hit.source());
                }
            }
            return results;
        } catch (IOException e) {
            log.error("Failed to find preference by key: {}", key, e);
            return List.of();
        }
    }

    /**
     * 更新已有偏好：用新的 value 和 content 替换，重新向量化。
     */
    public void update(String id, String userId, PreferenceItem item) {
        try {
            float[] vector = embeddingService.embed(item.content);

            PreferenceDocument doc = new PreferenceDocument(
                    id, userId, item.content, item.category, item.key, item.value,
                    toFloatList(vector), null, Instant.now().toString()
            );

            esClient.update(u -> u
                    .index(PreferenceMemoryIndexInitializer.PREFERENCES_INDEX)
                    .id(id)
                    .doc(doc)
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True),
                    PreferenceDocument.class);

            log.info("Updated preference: id={}, key={}, value={}", id, item.key, item.value);
        } catch (IOException e) {
            log.error("Failed to update preference: id={}, key={}", id, e);
        }
    }

    /**
     * 按 userId + key 删除已有偏好（覆盖场景：先删后插）。
     */
    public void deleteByKey(String userId, String key) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(PreferenceMemoryIndexInitializer.PREFERENCES_INDEX)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("userId").value(userId)))
                            .must(m -> m.term(t -> t.field("key").value(key)))
                    ))
                    .refresh(true)
            );
            esClient.deleteByQuery(request);
            log.debug("Deleted preference by key: userId={}, key={}", userId, key);
        } catch (IOException e) {
            log.error("Failed to delete preference by key: userId={}, key={}", userId, key, e);
        }
    }

    /**
     * 语义检索：用查询文本的向量搜索最相关的偏好（限定 userId）。
     */
    public List<PreferenceDocument> search(String userId, String queryText, int topK) {
        try {
            float[] queryVector = embeddingService.embed(queryText);
            int fetchSize = topK * 5;

            KnnSearch knn = KnnSearch.of(kq -> kq
                    .field("embedding")
                    .queryVector(toFloatList(queryVector))
                    .k(fetchSize)
                    .numCandidates(fetchSize * 2)
                    .filter(f -> f.term(t -> t.field("userId").value(userId)))
            );

            SearchRequest request = SearchRequest.of(s -> s
                    .index(PreferenceMemoryIndexInitializer.PREFERENCES_INDEX)
                    .knn(knn)
                    .size(fetchSize)
            );

            SearchResponse<PreferenceDocument> response = esClient.search(request, PreferenceDocument.class);
            List<Hit<PreferenceDocument>> hits = response.hits().hits();

            List<PreferenceDocument> results = new ArrayList<>();
            for (Hit<PreferenceDocument> hit : hits) {
                if (hit.source() != null) {
                    PreferenceDocument doc = hit.source();
                    doc.setId(hit.id());
                    results.add(doc);
                }
            }
            return results;
        } catch (IOException e) {
            log.error("Failed to search preferences: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取指定用户的全部偏好列表。
     */
    public List<PreferenceDocument> findAll(String userId) {
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(PreferenceMemoryIndexInitializer.PREFERENCES_INDEX)
                    .query(q -> q.term(t -> t.field("userId").value(userId)))
                    .size(100)
            );

            List<Hit<PreferenceDocument>> hits = esClient.search(request, PreferenceDocument.class)
                    .hits().hits();

            List<PreferenceDocument> results = new ArrayList<>();
            for (Hit<PreferenceDocument> hit : hits) {
                if (hit.source() != null) {
                    results.add(hit.source());
                }
            }
            return results;
        } catch (IOException e) {
            log.error("Failed to find all preferences", e);
            return List.of();
        }
    }

    /**
     * 清空指定用户的全部偏好。
     */
    public void clearAll(String userId) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(PreferenceMemoryIndexInitializer.PREFERENCES_INDEX)
                    .query(q -> q.term(t -> t.field("userId").value(userId)))
                    .refresh(true)
            );
            esClient.deleteByQuery(request);
            log.info("Cleared all preferences for userId={}", userId);
        } catch (IOException e) {
            log.error("Failed to clear preferences for userId={}", userId, e);
        }
    }

    private static List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }
}
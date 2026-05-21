package com.agent.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.agent.core.EmbeddingService;
import com.agent.core.Memory;
import com.agent.core.Message;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆实现：基于 ES {@code dense_vector(1024)} 的语义去重 + 合并 + 检索记忆。
 * <p>
 * <b>核心流程</b>：
 * <ol>
 *   <li><b>写入</b>：消息内容向量化 → ES KNN 搜索最近文档 →
 *       相似度 ≥ 0.95 则合并到旧文档，否则新建文档</li>
 *   <li><b>检索</b>：用户 Query 向量化 → ES KNN 余弦相似度搜索 → 返回 Top-K 语义最相关的历史消息</li>
 *   <li><b>会话过滤</b>：{@link #searchBySession} 在 KNN 基础上加 {@code sessionId} 过滤</li>
 * </ol>
 * <p>
 * <b>去重策略（纯向量，无哈希）</b>：
 * 每条消息向量化后，用该向量在 ES 中做 KNN(k=1) 查最近文档。
 * 如果最近文档的 {@code _score ≥ 1.95}（即余弦相似度 ≥ 0.95），认为语义重复，
 * 将新内容拼接到旧文档后面（分号分隔），重新向量化后更新 ES 文档。
 * <p>
 * <b>缓存层</b>：{@link #messageCache}（{@link ConcurrentHashMap}），写入 ES 后同步更新。
 * {@link #getRecent} / {@link #getAll} 直接走缓存不查 ES。
 * <p>
 * <b>异步写入</b>：{@link #addAsync} 标记 {@code @Async}，不阻塞主流程。
 * <p>
 * 与 {@link ConversationMemory} 的区别：
 * <ul>
 *   <li>ConversationMemory = 短窗口 + 时序 → "最近聊了什么"</li>
 *   <li>VectorMemory = ES 向量检索 + 语义去重 → "历史上聊过哪些和当前问题相关的内容"</li>
 * </ul>
 *
 * @see Memory
 * @see EsMemoryIndexInitializer
 */
@Slf4j
@Component
public class VectorMemory implements Memory {

    /** ES 长期记忆索引名 */
    public static final String MEMORY_INDEX = "agent_long_term_memory";

    /** KNN 搜索时 numCandidates = k × 3，平衡召回率与性能 */
    private static final int NUM_CANDIDATES_MULTIPLIER = 3;

    /**
     * 检索时多取几倍候选，因为可能有语义重复文档，
     * 取 k×3 条再截断到 k 条，保证返回的都是不重复的高质量结果
     */
    private static final int SEARCH_FETCH_MULTIPLIER = 3;

    /**
     * 语义合并阈值。ES 中 cosine similarity 的 _score = 1 + cosine。
     * 1.95 等价于 cosine ≥ 0.95。
     * <p>
     * bge-large-zh-v1.5 模型下：
     * <ul>
     *   <li>同义表述（"今天天气怎么样" vs "今天天气如何"）→ cosine ~0.96 → 合并</li>
     *   <li>不同话题（"Java死锁" vs "Python怎么学"）→ cosine ~0.20 → 不合并</li>
     * </ul>
     */
    private static final double SEMANTIC_MERGE_THRESHOLD = 1.95;

    /**
     * 当前关联的 sessionId，由 {@link MemoryManager#loadSessionMemory} 设置。
     * 写入 ES 文档时记为 {@code sessionId} 字段，方便按会话删除和过滤。
     */
    private String currentSessionId;

    /**
     * 内存级消息缓存，key = message.id。写入 ES 后同步更新。
     * 用途：{@link #getRecent} / {@link #getAll} 直接读内存，不查 ES。
     */
    @Getter
    private final Map<String, Message> messageCache = new ConcurrentHashMap<>();

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;

    public VectorMemory(ElasticsearchClient esClient, EmbeddingService embeddingService) {
        this.esClient = esClient;
        this.embeddingService = embeddingService;
    }

    public void setCurrentSessionId(String sessionId) {
        this.currentSessionId = sessionId;
    }

    @Override
    public MemoryType type() {
        return MemoryType.LONG_TERM;
    }

    // ======================== 写入（含语义去重+合并） ========================

    /**
     * 写入一条消息到长期记忆。
     * <p>
     * <b>两步去重决策</b>：
     * <ol>
     *   <li>向量化内容 → 1024 维向量</li>
     *   <li>用该向量在 ES 中 KNN 搜索最近的一条文档</li>
     *   <li>若 _score ≥ 阈值 → 合并到已有文档</li>
     *   <li>若 _score &lt; 阈值 → 全新写入</li>
     * </ol>
     * <p>
     * 没有 SHA-256 精确匹配——所有去重都通过向量语义相似度完成，
     * "今天天气怎么样" 和 "今天天气如何" 会被识别为语义相近并合并。
     */
    @Override
    public void add(Message message) {
        if (message.getContent() == null || message.getContent().isBlank()) {
            return;
        }

        float[] embedding = embeddingService.embed(message.getContent());
        String matchId = searchSemanticDuplicate(embedding);

        if (matchId != null) {
            mergeIntoExisting(matchId, message);
        } else {
            insertNew(message, embedding);
        }
    }

    /**
     * 异步写入，标记 {@code @Async} 使用 Spring 异步线程池。
     */
    @Async
    public void addAsync(Message message) {
        add(message);
    }

    /**
     * 批量写入，逐条调用 {@link #add}，每条独立做语义去重。
     */
    @Override
    public void addAll(List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        for (Message msg : messages) {
            add(msg);
        }
    }

    // ======================== 查询 ========================

    /**
     * 获取最近 N 条消息（按时间戳降序），直接读内存缓存，不查 ES。
     */
    @Override
    public List<Message> getRecent(int count) {
        return new ArrayList<>(messageCache.values().stream()
                .sorted(Comparator.comparing(Message::getTimestamp).reversed())
                .limit(count)
                .toList());
    }

    /**
     * 获取全部消息的副本，直接读内存缓存。
     */
    @Override
    public List<Message> getAll() {
        return new ArrayList<>(messageCache.values());
    }

    /**
     * 语义检索：将查询文本向量化后，在 ES 中做 KNN 余弦相似度搜索。
     * <p>
     * 多取 3 倍候选再截断，保证返回结果多样性。
     *
     * @param query 用户当前查询文本
     * @param k     返回 Top-K 条最相关的历史消息
     * @return 按相似度降序排列的历史消息列表
     */
    public List<Message> search(String query, int k) {
        float[] queryVector = embeddingService.embed(query);

        try {
            int fetchSize = k * SEARCH_FETCH_MULTIPLIER;

            KnnSearch knn = KnnSearch.of(kq -> kq
                    .field("embedding")
                    .queryVector(toFloatList(queryVector))
                    .k(fetchSize)
                    .numCandidates(fetchSize * NUM_CANDIDATES_MULTIPLIER)
            );

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(MEMORY_INDEX)
                    .knn(knn)
                    .size(fetchSize)
            );

            SearchResponse<MemoryDocument> response = esClient.search(searchRequest, MemoryDocument.class);
            return hitsToMessages(response.hits().hits(), k);
        } catch (IOException e) {
            log.error("Vector memory search failed", e);
            return List.of();
        }
    }

    /**
     * 带会话过滤的语义检索：限定在指定 session 内做 KNN 搜索。
     *
     * @param sessionId 会话 ID
     * @param query     用户查询文本
     * @param k         返回 Top-K 条
     * @return 指定会话内语义最相关的历史消息
     */
    public List<Message> searchBySession(String sessionId, String query, int k) {
        float[] queryVector = embeddingService.embed(query);

        try {
            int fetchSize = k * SEARCH_FETCH_MULTIPLIER;

            KnnSearch knn = KnnSearch.of(kq -> kq
                    .field("embedding")
                    .queryVector(toFloatList(queryVector))
                    .k(fetchSize)
                    .numCandidates(fetchSize * NUM_CANDIDATES_MULTIPLIER)
                    .filter(Query.of(q -> q
                            .term(t -> t
                                    .field("sessionId")
                                    .value(sessionId)
                            )
                    ))
            );

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(MEMORY_INDEX)
                    .knn(knn)
                    .size(fetchSize)
            );

            SearchResponse<MemoryDocument> response = esClient.search(searchRequest, MemoryDocument.class);
            return hitsToMessages(response.hits().hits(), k);
        } catch (IOException e) {
            log.error("Vector memory search by session failed", e);
            return List.of();
        }
    }

    // ======================== 删除 ========================

    /**
     * 按 sessionId 删除 ES 中的全部记忆（会话删除时级联调用）。
     * 使用 deleteByQuery 一条请求批量删除所有匹配文档。
     */
    public void deleteBySessionId(String sessionId) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(MEMORY_INDEX)
                    .refresh(true)
                    .query(q -> q
                            .term(t -> t
                                    .field("sessionId")
                                    .value(sessionId)
                            )
                    )
            );
            esClient.deleteByQuery(request);
            log.debug("Deleted ES memory for session {}", sessionId);
        } catch (IOException e) {
            log.error("Failed to delete memory by session {}", sessionId, e);
        }
    }

    @Override
    public void compact(int maxTokens) {
    }

    @Override
    public void clear() {
        messageCache.clear();
    }

    // ======================== 向量去重 + 合并核心 ========================

    /**
     * 语义去重搜索：将新消息的向量在 ES 中做 KNN(k=1) 搜索，
     * 找出最相似的一条已有文档。
     * <p>
     * 如果最近文档的 _score ≥ {@link #SEMANTIC_MERGE_THRESHOLD}（1.95），
     * 认为语义重复，返回该文档的 id；否则返回 null。
     * <p>
     * _score = 1 + cosine，其中 cosine 由 ES 配置的 similarity:cosine 自动计算。
     *
     * @param queryEmbedding 新消息的 1024 维向量
     * @return 语义重复文档的 ES _id，无重复时返回 null
     */
    private String searchSemanticDuplicate(float[] queryEmbedding) {
        try {
            KnnSearch knn = KnnSearch.of(kq -> kq
                    .field("embedding")
                    .queryVector(toFloatList(queryEmbedding))
                    .k(1)
                    .numCandidates(10)
            );

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(MEMORY_INDEX)
                    .knn(knn)
                    .size(1)
            );

            SearchResponse<MemoryDocument> response = esClient.search(searchRequest, MemoryDocument.class);
            List<Hit<MemoryDocument>> hits = response.hits().hits();
            if (!hits.isEmpty() && hits.get(0).score() != null
                    && hits.get(0).score() >= SEMANTIC_MERGE_THRESHOLD) {
                return hits.get(0).id();
            }
        } catch (IOException e) {
            log.warn("Semantic dedup search failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 将新消息合并到已有 ES 文档。
     * <p>
     * <b>合并步骤</b>：
     * <ol>
     *   <li>从 ES 拉出旧文档完整内容</li>
     *   <li>拼接：旧内容 + "；" + 新内容</li>
     *   <li>重新向量化合并后的内容（保证新向量反映完整语义）</li>
     *   <li>ES update：更新 content、embedding、mergeCount(+1)、updatedAt</li>
     *   <li>同步更新 messageCache</li>
     * </ol>
     *
     * @param esId    已有文档的 ES _id
     * @param message 新消息
     */
    private void mergeIntoExisting(String esId, Message message) {
        try {
            MemoryDocument existing = esClient.get(g -> g
                    .index(MEMORY_INDEX).id(esId), MemoryDocument.class).source();

            String mergedContent = existing.getContent() + "；" + message.getContent();
            int mergeCount = (existing.getMergeCount() != null ? existing.getMergeCount() : 1) + 1;
            float[] mergedEmbedding = embeddingService.embed(mergedContent);

            String now = Instant.now().toString();

            UpdateRequest<MemoryDocument, MemoryDocument> update = UpdateRequest.of(u -> u
                    .index(MEMORY_INDEX)
                    .id(esId)
                    .doc(new MemoryDocument(
                            esId, existing.getRole(), mergedContent,
                            toFloatList(mergedEmbedding), existing.getSessionId(),
                            existing.getCreatedAt(), mergeCount, now
                    ))
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)
            );
            esClient.update(update, MemoryDocument.class);

            Message mergedMsg = new Message(
                    Message.Role.valueOf(existing.getRole()), mergedContent);
            mergedMsg.setId(esId);
            mergedMsg.setTimestamp(Instant.parse(now));
            messageCache.put(esId, mergedMsg);

            log.debug("Merged into {} (mergeCount={})", esId, mergeCount);
        } catch (IOException e) {
            log.error("Failed to merge into existing doc {}: {}", esId, e.getMessage());
        }
    }

    /**
     * 全新写入：将已向量化的消息写入 ES 并同步缓存。
     * <p>
     * 写入的文档包含：id（UUID）、role、content、embedding、sessionId、
     * createdAt、mergeCount=1、updatedAt=now。
     *
     * @param message   新消息
     * @param embedding 已计算好的 1024 维向量（由调用方传入，避免重复向量化）
     */
    private void insertNew(Message message, float[] embedding) {
        try {
            String id = (message.getId() != null) ? message.getId()
                    : UUID.randomUUID().toString().replace("-", "");
            String now = Instant.now().toString();
            MemoryDocument doc = new MemoryDocument(
                    id, message.getRole().name(), message.getContent(),
                    toFloatList(embedding), currentSessionId, now, 1, now
            );

            esClient.index(i -> i
                    .index(MEMORY_INDEX).id(id).document(doc)
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));

            messageCache.put(id, message);
        } catch (IOException e) {
            log.error("Failed to index message to ES memory", e);
        }
    }

    /**
     * 将 ES 搜索结果转换为 Message 列表，截断到 maxResults 条。
     */
    private List<Message> hitsToMessages(List<Hit<MemoryDocument>> hits, int maxResults) {
        List<Message> result = new ArrayList<>();
        for (Hit<MemoryDocument> hit : hits) {
            MemoryDocument doc = hit.source();
            if (doc == null) {
                continue;
            }
            Message msg = new Message(Message.Role.valueOf(doc.getRole()), doc.getContent());
            msg.setId(doc.getId());
            msg.setTimestamp(doc.getUpdatedAt() != null
                    ? Instant.parse(doc.getUpdatedAt()) : Instant.parse(doc.getCreatedAt()));
            result.add(msg);
            if (result.size() >= maxResults) {
                break;
            }
        }
        return result;
    }

    /**
     * float[] → List&lt;Float&gt;。
     * ES Java Client 要求 dense_vector 类型为 {@code List<Float>} 而非 {@code float[]}。
     */
    private List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) {
            list.add(v);
        }
        return list;
    }

    // ======================== ES 文档内部类 ========================

    /**
     * ES {@code agent_long_term_memory} 索引的文档映射 POJO。
     * 字段与索引 mapping 一一对应，由 ES Java Client 自动序列化/反序列化。
     */
    @Getter
    @Setter
    public static class MemoryDocument {
        /** 消息唯一标识（UUID） */
        private String id;
        /** SYSTEM / USER / ASSISTANT / TOOL */
        private String role;
        /** 消息文本内容，合并后为多次拼接结果（分号分隔） */
        private String content;
        /** 1024 维稠密向量，对应 ES dense_vector 类型 */
        private List<Float> embedding;
        /** 所属会话 ID，用于按会话过滤和级联删除 */
        private String sessionId;
        /** 首次创建时间（ISO-8601 格式），写入后不再修改 */
        private String createdAt;
        /** 语义合并次数，首次写入为 1，每次合并 +1 */
        private Integer mergeCount;
        /** 最后更新时间（ISO-8601 格式），每次合并后刷新 */
        private String updatedAt;

        public MemoryDocument() {
        }

        public MemoryDocument(String id, String role, String content,
                              List<Float> embedding, String sessionId,
                              String createdAt, Integer mergeCount, String updatedAt) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.embedding = embedding;
            this.sessionId = sessionId;
            this.createdAt = createdAt;
            this.mergeCount = mergeCount;
            this.updatedAt = updatedAt;
        }
    }
}

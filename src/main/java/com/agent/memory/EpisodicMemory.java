package com.agent.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.agent.core.EmbeddingService;
import com.agent.llm.DeepSeekChatClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 情景记忆：在会话中每隔 K 轮或结束时，将短期记忆中的对话提炼为结构化情景摘要。
 * <p>
 * 与旧的 VectorMemory 的关键区别：
 * <ul>
 *   <li>不再逐条存储每条消息，而是将整个会话压缩为一条情景摘要</li>
 *   <li>提取 <b>话题标签</b>、<b>关键实体</b>、<b>意图序列</b> 等结构化字段</li>
 *   <li>打上时间戳和 sessionId，方便按时间和会话检索</li>
 * </ul>
 * <p>
 * 检索时用 summary 的语义向量做 KNN，命中后将结构化字段一并返回供 LLM 参考。
 */
@Slf4j
@Component
public class EpisodicMemory {

    private static final String EXTRACT_PROMPT = """
            你是一个对话情景分析助手。请将以下对话提炼为一条情景记忆。

            ## 对话内容
            {conversation}

            ## 输出要求
            返回严格 JSON，字段说明：
            - summary: 一句话概括这段对话的核心内容（30字以内）
            - topicTags: 话题标签列表，如 ["Java学习", "职业规划"]
            - keyEntities: 关键实体列表，如 ["Spring Boot", "后端开发", "北京"]
            - intentSequence: 用户意图变化序列，如 ["询问学习方法", "表达偏好", "请求推荐"]

            ## 输出格式
            {
              "summary": "...",
              "topicTags": ["...", "..."],
              "keyEntities": ["...", "..."],
              "intentSequence": ["...", "..."]
            }
            """;

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;
    private final DeepSeekChatClient chatClient;
    private final ObjectMapper objectMapper;

    public EpisodicMemory(ElasticsearchClient esClient, EmbeddingService embeddingService,
                          DeepSeekChatClient chatClient, ObjectMapper objectMapper) {
        this.esClient = esClient;
        this.embeddingService = embeddingService;
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 从对话文本中提取情景记忆并写入 ES。
     * 异步调用，不阻塞主流程。
     *
     * @param sessionId    会话 ID
     * @param conversation 对话文本（由 ConversationMemory.buildContextPrompt() 提供）
     */
    @Async
    public void extractAndStore(String sessionId, String conversation) {
        if (conversation == null || conversation.isBlank()) {
            log.debug("Empty conversation, skip episodic memory extraction for session={}", sessionId);
            return;
        }

        try {
            String prompt = EXTRACT_PROMPT.replace("{conversation}", conversation);
            String response = chatClient.chat(prompt);

            EpisodicRecord record = parseResponse(response);
            if (record == null) {
                log.warn("Failed to parse episodic memory extraction for session={}", sessionId);
                return;
            }

            storeEpisode(sessionId, record);
            log.info("Episodic memory stored: session={}, summary={}, tags={}, entities={}",
                    sessionId, record.summary, record.topicTags, record.keyEntities);
        } catch (Exception e) {
            log.error("Episodic memory extraction failed for session={}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 语义检索情景记忆：用查询文本向量搜索最相关的情景摘要。
     */
    public List<EpisodicMemoryDocument> search(String queryText, int topK) {
        try {
            float[] queryVector = embeddingService.embed(queryText);
            int fetchSize = topK * 5;

            KnnSearch knn = KnnSearch.of(kq -> kq
                    .field("embedding")
                    .queryVector(toFloatList(queryVector))
                    .k(fetchSize)
                    .numCandidates(fetchSize * 2)
            );

            SearchRequest request = SearchRequest.of(s -> s
                    .index(EpisodicMemoryIndexInitializer.EPISODIC_INDEX)
                    .knn(knn)
                    .size(fetchSize)
            );

            SearchResponse<EpisodicMemoryDocument> response =
                    esClient.search(request, EpisodicMemoryDocument.class);

            List<EpisodicMemoryDocument> results = new ArrayList<>();
            for (Hit<EpisodicMemoryDocument> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    EpisodicMemoryDocument doc = hit.source();
                    doc.setId(hit.id());
                    results.add(doc);
                }
            }
            return results;
        } catch (IOException e) {
            log.error("Episodic memory search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 sessionId 删除相关的所有情景记忆。
     */
    public void deleteBySessionId(String sessionId) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(EpisodicMemoryIndexInitializer.EPISODIC_INDEX)
                    .query(q -> q.term(t -> t.field("sessionId").value(sessionId)))
                    .refresh(true)
            );
            esClient.deleteByQuery(request);
            log.info("Deleted episodic memory for session={}", sessionId);
        } catch (IOException e) {
            log.error("Failed to delete episodic memory for session={}", sessionId, e);
        }
    }

    /**
     * 构建可注入 LLM 上下文的情景记忆文本。
     */
    public String buildEpisodeContextText(String queryText, int topK) {
        List<EpisodicMemoryDocument> episodes = search(queryText, topK);
        if (episodes.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【相关历史情景记忆】\n");
        for (EpisodicMemoryDocument ep : episodes) {
            sb.append("- 摘要：").append(ep.getSummary()).append("\n");
            if (ep.getTopicTags() != null && !ep.getTopicTags().isEmpty()) {
                sb.append("  话题：").append(String.join(", ", ep.getTopicTags())).append("\n");
            }
            if (ep.getKeyEntities() != null && !ep.getKeyEntities().isEmpty()) {
                sb.append("  关键实体：").append(String.join(", ", ep.getKeyEntities())).append("\n");
            }
            if (ep.getIntentSequence() != null && !ep.getIntentSequence().isEmpty()) {
                sb.append("  意图序列：").append(String.join(" → ", ep.getIntentSequence())).append("\n");
            }
        }
        return sb.toString();
    }

    private void storeEpisode(String sessionId, EpisodicRecord record) {
        try {
            String searchText = record.summary + " " + String.join(" ", record.topicTags)
                    + " " + String.join(" ", record.keyEntities);
            float[] vector = embeddingService.embed(searchText);

            EpisodicMemoryDocument doc = new EpisodicMemoryDocument(
                    UUID.randomUUID().toString().replace("-", ""),
                    sessionId,
                    record.summary,
                    record.topicTags,
                    record.keyEntities,
                    record.intentSequence,
                    toFloatList(vector),
                    Instant.now().toString()
            );

            esClient.index(i -> i
                    .index(EpisodicMemoryIndexInitializer.EPISODIC_INDEX)
                    .id(doc.getId())
                    .document(doc)
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
        } catch (IOException e) {
            log.error("Failed to store episodic memory for session={}", sessionId, e);
        }
    }

    private EpisodicRecord parseResponse(String response) {
        try {
            String json = extractJson(response);
            if (json == null) {
                return null;
            }
            Map<String, Object> map = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() {});

            EpisodicRecord record = new EpisodicRecord();
            record.summary = (String) map.getOrDefault("summary", "");
            record.topicTags = toList(map.get("topicTags"));
            record.keyEntities = toList(map.get("keyEntities"));
            record.intentSequence = toList(map.get("intentSequence"));
            return record;
        } catch (Exception e) {
            log.warn("Failed to parse episodic memory JSON: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> toList(Object obj) {
        if (obj instanceof List) {
            return (List<String>) obj;
        }
        return List.of();
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private static List<Float> toFloatList(float[] array) {
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        return list;
    }

    static class EpisodicRecord {
        String summary;
        List<String> topicTags;
        List<String> keyEntities;
        List<String> intentSequence;
    }
}
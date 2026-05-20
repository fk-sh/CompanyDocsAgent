package com.agent.vectordb;

import com.agent.core.EmbeddingService;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Embedding 服务实现，调用 OpenAI 兼容的 Embedding API 生成稠密向量。
 * <p>
 * 核心流程：
 * <ol>
 *   <li>先查 {@link EmbeddingCache}（Redis），命中则直接返回</li>
 *   <li>未命中则 POST 到 {@code /v1/embeddings}，获取 bge-large-zh-v1.5 的 1024 维向量</li>
 *   <li>写入缓存供后续复用</li>
 * </ol>
 * <p>
 * 批量向量化 {@link #embedBatch(List)} 采用两级优化：
 * <ul>
 *   <li>先批查缓存，只对未命中的文本调用 API</li>
 *   <li>按 {@link EmbeddingConfig#getBatchSize()} 分批发送，避免单次请求过大</li>
 * </ul>
 */
@Slf4j
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final WebClient webClient;
    private final EmbeddingConfig config;
    private final EmbeddingCache cache;
    private final ObjectMapper objectMapper;

    public EmbeddingServiceImpl(WebClient embeddingWebClient, EmbeddingConfig config,
                                EmbeddingCache cache, ObjectMapper objectMapper) {
        this.webClient = embeddingWebClient;
        this.config = config;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    @Override
    /**
     * 将单条文本向量化,文本向量化为1024 维 float 向量
     *
     * @param text 输入文本
     * @return 1024 维 float 向量
     */
    public float[] embed(String text) {
        return cache.get(text).orElseGet(() -> {
            float[] embedding = callEmbeddingApi(List.of(text)).get(0);
            cache.put(text, embedding);
            return embedding;
        });
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> result = new ArrayList<>();
        List<String> uncachedTexts = new ArrayList<>();// 未命中缓存的文本
        List<Integer> uncachedIndices = new ArrayList<>();// 未命中缓存的文本索引

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            Optional<float[]> cached = cache.get(text);// 从缓存中获取向量，可能为空
            if (cached.isPresent()) {// 缓存命中
                result.add(cached.get());// 直接返回缓存中的向量，交给调用者处理，存储进ES向量数据库中
            } else {
                // 缓存未命中，调用 API 给它们生成向量，方便后边添加data到ES向量数据库中
                result.add(null);
                uncachedTexts.add(text);// 未命中缓存的文本
                uncachedIndices.add(i);// 未命中缓存的文本索引
            }
        }

        // ------------处理未命中缓存的文本----------------
        if (!uncachedTexts.isEmpty()) {
            //将文本按批次大小分批，避免单次请求过大
            List<List<String>> batches = partition(uncachedTexts, config.getBatchSize());
            int offset = 0;

            // 遍历每个批次，调用 API 生成向量
            for (List<String> batch : batches) {
                // 调用 API 生成向量
                List<float[]> batchEmbeddings = callEmbeddingApi(batch);
                for (int j = 0; j < batch.size(); j++) {
                    // 存储到缓存中，方便后续使用
                    // 计算全局索引，将新生成的向量批量存储到ES向量数据库中 ，并返回给调用者
                    int globalIndex = uncachedIndices.get(offset + j);
                    result.set(globalIndex, batchEmbeddings.get(j));// 将新生成的向量存储到结果列表中
                    cache.put(batch.get(j), batchEmbeddings.get(j));// 同时将新生成的向量存储到redis缓存中
                }
                offset += batch.size();
            }
        }

        return result;
    }

    @Override
    // 返回向量维度
    public int dimension() {
        return config.getDimension();
    }

    /**
     * 调用 Embedding API 批量生成向量。
     * 请求体格式遵循 OpenAI compatible 规范：{@code {"model": "...", "input": [...]}}。
     */
    private List<float[]> callEmbeddingApi(List<String> texts) {
        EmbeddingRequest request = new EmbeddingRequest(config.getModelName(), texts);

        try {
            String responseBody = webClient.post()
                    .uri("/v1/embeddings")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.isError(), clientResponse ->
                            clientResponse.bodyToMono(String.class).flatMap(body ->
                                    Mono.error(new RuntimeException(
                                            "Embedding API error: " + clientResponse.statusCode() + " body: " + body))
                            ))
                    .bodyToMono(String.class)
                    .block();

            @SuppressWarnings("unchecked")
            Map<String, Object> responseMap = objectMapper.readValue(responseBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) responseMap.get("data");

            List<float[]> embeddings = new ArrayList<>();
            for (Map<String, Object> item : data) {
                @SuppressWarnings("unchecked")
                List<Double> embeddingList = (List<Double>) item.get("embedding");
                float[] embedding = new float[embeddingList.size()];
                for (int i = 0; i < embeddingList.size(); i++) {
                    embedding[i] = embeddingList.get(i).floatValue();
                }
                embeddings.add(embedding);
            }

            log.debug("Generated {} embeddings for {} texts, dimension={}",
                    embeddings.size(), texts.size(), embeddings.isEmpty() ? 0 : embeddings.get(0).length);
            return embeddings;
        } catch (Exception e) {
            log.error("Failed to call embedding API for {} texts", texts.size(), e);
            throw new RuntimeException("Embedding API call failed", e);
        }
    }

    /**
     * 将列表按指定大小分批。
     * 
     */
    //## 为什么需要它？
    // Ollama Embedding API 一次能处理的文本数量有限制。如果把 100 条文本一次发过去，可能超时或 OOM。所以要切成小批：
    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class EmbeddingRequest {
        private String model;
        private List<String> input;
    }
}

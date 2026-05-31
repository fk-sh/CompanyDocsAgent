package com.agent.vectordb;

import com.agent.core.EmbeddingService;
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

@Slf4j
@Service
public class EmbeddingServiceImpl implements EmbeddingService {

    private final WebClient webClient;
    private final EmbeddingConfig config;
    private final ObjectMapper objectMapper;

    public EmbeddingServiceImpl(WebClient embeddingWebClient, EmbeddingConfig config, ObjectMapper objectMapper) {
        this.webClient = embeddingWebClient;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public float[] embed(String text) {
        return callEmbeddingApi(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>();
        List<List<String>> batches = partition(texts, config.getBatchSize());
        for (List<String> batch : batches) {
            result.addAll(callEmbeddingApi(batch));
        }
        return result;
    }

    @Override
    public int dimension() {
        return config.getDimension();
    }

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

            log.debug("Generated {} embeddings for {} texts", embeddings.size(), texts.size());
            return embeddings;
        } catch (Exception e) {
            log.error("Failed to call embedding API for {} texts", texts.size(), e);
            throw new RuntimeException("Embedding API call failed", e);
        }
    }

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
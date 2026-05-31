package com.agent.vectordb;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.cat.plugins.PluginsRecord;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnBean(ElasticsearchClient.class)
public class EsIndexInitializer implements CommandLineRunner {

    public static final String CHUNKS_INDEX = "agent_chunks";

    private final ElasticsearchClient esClient;
    private final Environment env;

    public EsIndexInitializer(ElasticsearchClient esClient, Environment env) {
        this.esClient = esClient;
        this.env = env;
    }

    @Override
    public void run(String... args) throws Exception {
        boolean exists = esClient.indices().exists(
                ExistsRequest.of(e -> e.index(CHUNKS_INDEX))
        ).value();

        boolean forceRecreate = "true".equals(env.getProperty("spring.elasticsearch.index.force-recreate"));

        if (exists && forceRecreate) {
            log.info("Force recreating ES index '{}'", CHUNKS_INDEX);
            esClient.indices().delete(d -> d.index(CHUNKS_INDEX));
            exists = false;
        }

        if (exists) {
            log.info("ES index '{}' already exists, skipping creation", CHUNKS_INDEX);
            return;
        }

        boolean ikAvailable = isIkAnalyzerAvailable();

        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> {
            var settings = c.index(CHUNKS_INDEX)
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                            .refreshInterval(interval -> interval.time("1s"))
                    );

            if (ikAvailable) {
                Map<String, JsonData> analysisSettings = new LinkedHashMap<>();
                analysisSettings.put("analysis", JsonData.of(
                        Map.of("analyzer", Map.of(
                                "ik_smart_analyzer", Map.of("type", "ik_smart"),
                                "ik_max_word_analyzer", Map.of("type", "ik_max_word")
                        ))
                ));
                settings.settings(s -> s.otherSettings(analysisSettings));
            }

            return settings.mappings(m -> m
                    .properties("id", p -> p.keyword(k -> k))
                    .properties("documentId", p -> p.keyword(k -> k))
                    .properties("parentChunkId", p -> p.keyword(k -> k))
                    .properties("contentType", p -> p.keyword(k -> k))
                    .properties("content", p -> p.text(t -> {
                        if (ikAvailable) {
                            t.analyzer("ik_max_word_analyzer")
                             .searchAnalyzer("ik_smart_analyzer");
                        } else {
                            t.analyzer("standard");
                        }
                        return t;
                    }))
                    .properties("chunkIndex", p -> p.integer(i -> i))
                    .properties("startOffset", p -> p.integer(i -> i))
                    .properties("endOffset", p -> p.integer(i -> i))
                    .properties("embedding", p -> p.denseVector(dv -> dv
                            .dims(1024)
                            .index(true)
                            .similarity("cosine")
                    ))
                    .properties("metadata", p -> p.object(o -> o))
                    .properties("fileName", p -> p.keyword(k -> k))
                    .properties("uploaderName", p -> p.keyword(k -> k))
                    .properties("department", p -> p.keyword(k -> k))
                    .properties("visibility", p -> p.keyword(k -> k))
                    .properties("documentStatus", p -> p.keyword(k -> k))
                    .properties("disabled", p -> p.boolean_(b -> b))
                    .properties("createdAt", p -> p.keyword(k -> k))
            );
        });

        esClient.indices().create(createRequest);
        if (ikAvailable) {
            log.info("ES index '{}' created with IK Chinese analyzer (ik_max_word / ik_smart)", CHUNKS_INDEX);
        } else {
            log.info("ES index '{}' created with standard analyzer. Tip: install IK with `./bin/elasticsearch-plugin install analysis-ik`", CHUNKS_INDEX);
        }
    }

    private boolean isIkAnalyzerAvailable() {
        try {
            java.util.List<PluginsRecord> plugins = esClient.cat().plugins().valueBody();
            for (PluginsRecord p : plugins) {
                if (p.component() != null && p.component().contains("analysis-ik")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("Failed to check IK analyzer availability: {}", e.getMessage());
            return false;
        }
    }
}

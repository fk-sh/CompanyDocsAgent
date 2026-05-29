package com.agent.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnBean(ElasticsearchClient.class)
public class EpisodicMemoryIndexInitializer implements CommandLineRunner {

    public static final String EPISODIC_INDEX = "agent_episodic_memory";

    private final ElasticsearchClient esClient;

    public EpisodicMemoryIndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public void run(String... args) throws Exception {
        boolean exists = esClient.indices().exists(
                ExistsRequest.of(e -> e.index(EPISODIC_INDEX))
        ).value();

        if (exists) {
            log.info("ES index '{}' already exists, skipping creation", EPISODIC_INDEX);
            return;
        }

        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                .index(EPISODIC_INDEX)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                        .refreshInterval(interval -> interval.time("1s"))
                )
                .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))
                        .properties("sessionId", p -> p.keyword(k -> k))
                        .properties("summary", p -> p.text(t -> t.analyzer("standard")))
                        .properties("topicTags", p -> p.keyword(k -> k))
                        .properties("keyEntities", p -> p.keyword(k -> k))
                        .properties("intentSequence", p -> p.keyword(k -> k))
                        .properties("embedding", p -> p.denseVector(dv -> dv
                                .dims(1024)
                                .index(true)
                                .similarity("cosine")
                        ))
                        .properties("createdAt", p -> p.date(d -> d))
                )
        );

        esClient.indices().create(createRequest);
        log.info("ES index '{}' created with dense_vector(1024)", EPISODIC_INDEX);
    }
}
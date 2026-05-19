package com.agent.vectordb;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * ES 索引初始化器，应用启动时自动检查并创建索引。
 * <p>
 * 索引 {@code agent_chunks} 的核心 mapping：
 * <ul>
 *   <li>{@code embedding} — {@code dense_vector(1024)}，启用索引，余弦相似度</li>
 *   <li>{@code content} — {@code text} 类型，使用 standard 分词器（用于 BM25 检索）</li>
 *   <li>{@code id / documentId / parentChunkId} — {@code keyword} 精确匹配</li>
 *   <li>{@code metadata} — {@code object} 类型，存储自定义元数据</li>
 * </ul>
 * <p>
 * 仅在 {@link ElasticsearchClient} Bean 可用时激活。
 */
@Slf4j
@Component
@ConditionalOnBean(ElasticsearchClient.class)
public class EsIndexInitializer implements CommandLineRunner {

    /** ES 索引名称 */
    public static final String CHUNKS_INDEX = "agent_chunks";

    private final ElasticsearchClient esClient;

    public EsIndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public void run(String... args) throws Exception {
        ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(CHUNKS_INDEX));
        boolean exists = esClient.indices().exists(existsRequest).value();

        if (exists) {
            log.info("ES index '{}' already exists, skipping creation", CHUNKS_INDEX);
            return;
        }

        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                .index(CHUNKS_INDEX)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                        .refreshInterval(interval -> interval.time("1s"))
                )
                .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))
                        .properties("documentId", p -> p.keyword(k -> k))
                        .properties("parentChunkId", p -> p.keyword(k -> k))
                        .properties("contentType", p -> p.keyword(k -> k))
                        .properties("content", p -> p.text(t -> t.analyzer("standard")))
                        .properties("chunkIndex", p -> p.integer(i -> i))
                        .properties("startOffset", p -> p.integer(i -> i))
                        .properties("endOffset", p -> p.integer(i -> i))
                        .properties("embedding", p -> p.denseVector(dv -> dv
                                .dims(1024)
                                .index(true)
                                .similarity("cosine")
                        ))
                        .properties("metadata", p -> p.object(o -> o))
                        .properties("createdAt", p -> p.date(d -> d))
                )
        );

        esClient.indices().create(createRequest);
        log.info("ES index '{}' created with dense_vector(1024) mapping", CHUNKS_INDEX);
    }
}

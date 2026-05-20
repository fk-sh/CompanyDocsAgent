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
        // 检查索引是否已存在，避免重复创建相当于mysql的create table if not exists
        boolean exists = esClient.indices().exists(
                ExistsRequest.of(e -> e.index(CHUNKS_INDEX))
        ).value();

        if (exists) {
            log.info("ES index '{}' already exists, skipping creation", CHUNKS_INDEX);
            return;
        }

        // 创建索引
        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                .index(CHUNKS_INDEX)
                .settings(s -> s
                        .numberOfShards("1")// 单分片，避免分片迁移
                        .numberOfReplicas("0")// 无副本，避免副本迁移
                        .refreshInterval(interval -> interval.time("1s"))// 刷新间隔，写入后最多等 1 秒就可被搜索到
                )
                // 定义映射，相当于mysql中的字段定义
                .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))// 精确匹配
                        .properties("documentId", p -> p.keyword(k -> k))// 精确匹配
                        .properties("parentChunkId", p -> p.keyword(k -> k))// 精确匹配
                        .properties("contentType", p -> p.keyword(k -> k))// 精确匹配
                        // ─── 全文检索字段（分词 + BM25）───
                        // standard 分词器：中文按字切、英文按词切
                        .properties("content", p -> p.text(t -> t.analyzer("standard")))
                        // ─── 数值字段 ───
                        .properties("chunkIndex", p -> p.integer(i -> i))
                        .properties("startOffset", p -> p.integer(i -> i))
                        .properties("endOffset", p -> p.integer(i -> i))
                        // ─── 向量字段（KNN 检索核心）───
                        .properties("embedding", p -> p.denseVector(dv -> dv
                                .dims(1024)// 1024 维向量，与 EmbeddingConfig 中的维度一致
                                .index(true)// 启用索引，加速 KNN 搜索
                                .similarity("cosine")// 余弦相似度，用于计算向量之间的相似度
                        ))
                        // ─── 动态字段 ───
                        .properties("metadata", p -> p.object(o -> o))// 存储自定义元数据
                        .properties("createdAt", p -> p.date(d -> d))// 创建时间
                )
        );

        esClient.indices().create(createRequest);// 创建索引
        log.info("ES index '{}' created with dense_vector(1024) mapping", CHUNKS_INDEX);
    }
}

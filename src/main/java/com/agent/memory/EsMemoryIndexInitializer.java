package com.agent.memory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * ES 长期记忆索引初始化器，应用启动时自动检查并创建索引映射。
 * <p>
 * <b>启动流程</b>：
 * <ol>
 *   <li>检查索引 {@code agent_long_term_memory} 是否存在</li>
 *   <li>不存在 → 调用 {@link #createIndex()} 新建索引（含完整 mapping）</li>
 *   <li>已存在 → 调用 {@link #ensureNewFields()} 增量添加新字段（向下兼容已有索引）</li>
 * </ol>
 * <p>
 * <b>索引字段说明</b>：
 * <table border="1">
 *   <tr><th>字段</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>id</td><td>keyword</td><td>消息唯一标识</td></tr>
 *   <tr><td>role</td><td>keyword</td><td>SYSTEM / USER / ASSISTANT / TOOL</td></tr>
 *   <tr><td>content</td><td>text(standard)</td><td>消息正文，支持 BM25 全文检索</td></tr>
 *   <tr><td>sessionId</td><td>keyword</td><td>所属会话 ID，用于按会话过滤和级联删除</td></tr>
 *   <tr><td>embedding</td><td>dense_vector(1024)</td><td>1024 维语义向量，余弦相似度，启用 HNSW 索引</td></tr>
 *   <tr><td>createdAt</td><td>date</td><td>首次创建时间</td></tr>
 *   <tr><td>mergeCount</td><td>integer</td><td>语义去重合并次数，首次写入为 1</td></tr>
 *   <tr><td>updatedAt</td><td>date</td><td>最后更新时间（每次合并后刷新）</td></tr>
 * </table>
 * <p>
 * 仅在 {@link ElasticsearchClient} Bean 可用时激活（即配置了 {@code spring.elasticsearch.uris}）。
 *
 * @see VectorMemory
 */
@Slf4j
@Component
@ConditionalOnBean(ElasticsearchClient.class)
public class EsMemoryIndexInitializer implements CommandLineRunner {

    private final ElasticsearchClient esClient;

    public EsMemoryIndexInitializer(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public void run(String... args) throws Exception {
        boolean exists = esClient.indices().exists(
                ExistsRequest.of(e -> e.index(VectorMemory.MEMORY_INDEX))
        ).value();

        if (!exists) {
            createIndex();
        } else {
            ensureNewFields();
        }
    }

    /**
     * 新建索引，包含完整字段映射。
     * <p>
     * 配置要点：
     * <ul>
     *   <li>单分片、零副本 — 开发环境不需要分布式容错</li>
     *   <li>1s 刷新间隔 — 写入后最多等 1 秒即可被 KNN 搜索命中</li>
     *   <li>{@code embedding} 字段指定 {@code similarity: cosine} — ES 用余弦相似度计算 _score</li>
     * </ul>
     */
    private void createIndex() throws Exception {
        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                .index(VectorMemory.MEMORY_INDEX)
                .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                        .refreshInterval(interval -> interval.time("1s"))
                )
                .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))
                        .properties("role", p -> p.keyword(k -> k))
                        .properties("content", p -> p.text(t -> t.analyzer("standard")))
                        .properties("sessionId", p -> p.keyword(k -> k))
                        .properties("embedding", p -> p.denseVector(dv -> dv
                                .dims(1024)
                                .index(true)
                                .similarity("cosine")
                        ))
                        .properties("createdAt", p -> p.date(d -> d))
                        .properties("mergeCount", p -> p.integer(i -> i))
                        .properties("updatedAt", p -> p.date(d -> d))
                )
        );

        esClient.indices().create(createRequest);
        log.info("ES index '{}' created with dense_vector(1024)", VectorMemory.MEMORY_INDEX);
    }

    /**
     * 对已存在的索引增量添加新字段（putMapping）。
     * <p>
     * 新字段默认值为 null，不会影响已有文档。异常被静默吞掉，
     * 因为字段可能已经存在（幂等设计的兜底）。
     */
    private void ensureNewFields() throws Exception {
        try {
            PutMappingRequest putMapping = PutMappingRequest.of(m -> m
                    .index(VectorMemory.MEMORY_INDEX)
                    .properties("mergeCount", p -> p.integer(i -> i))
                    .properties("updatedAt", p -> p.date(d -> d))
            );
            esClient.indices().putMapping(putMapping);
            log.info("ES index '{}' mapping updated: mergeCount / updatedAt", VectorMemory.MEMORY_INDEX);
        } catch (Exception e) {
            log.debug("Fields may already exist: {}", e.getMessage());
        }
    }
}

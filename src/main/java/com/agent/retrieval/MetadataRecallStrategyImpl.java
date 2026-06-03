package com.agent.retrieval;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.agent.core.Chunk;
import com.agent.core.RecallStrategy;
import com.agent.vectordb.ChunkDocument;
import com.agent.vectordb.EsIndexInitializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component("metadataRecall")
// 使用元数据进行元量召回策略实现类
public class MetadataRecallStrategyImpl implements RecallStrategy {

    private final ElasticsearchClient esClient;

    public MetadataRecallStrategyImpl(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    @Override
    public String name() {
        return "metadata_filter";
    }

    @Override
    public List<Chunk> recall(String query, int topK) {
        return recall(query, topK, java.util.Map.of());
    }

    @Override
    //只在父 Chunk 中召回，不召回子 Chunk
    // 实现元数据召回策略，根据查询文本返回 Top-K 个相似的 Chunk，仅返回父 Chunk ，且标题命中权重高
    public List<Chunk> recall(String query, int topK, java.util.Map<String, Object> filters) {
        //构建搜索请求
        try {
            List<Query> filterQueries = new ArrayList<>();
            filterQueries.add(Query.of(f -> f.term(t -> t
                    .field("metadata.isParent")
                    .value(co.elastic.clients.elasticsearch._types.FieldValue.of(true))
            )));
            Query permissionFilter = buildPermissionFilter(filters);
            if (permissionFilter != null) {
                filterQueries.add(permissionFilter);
            }

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(EsIndexInitializer.CHUNKS_INDEX)
                    .query(q -> q.bool(b -> b
                            .should(s1 -> s1.matchPhrase(mp -> mp
                                    .field("metadata.sectionTitle")
                                    .query(query)
                                    .boost(5.0F)
                            ))
                            .should(s2 -> s2.match(m -> m
                                    .field("metadata.sectionTitle")
                                    .query(query)
                                    .boost(3.0F)
                            ))
                            .should(s3 -> s3.matchPhrase(mp -> mp
                                    .field("content")
                                    .query(query)
                                    .boost(2.0F)
                            ))
                            .should(s4 -> s4.match(m -> m
                                    .field("content")
                                    .query(query)
                                    .boost(1.0F)
                            ))
                            .minimumShouldMatch("1")
                            .filter(filterQueries)
                    ))
                    .size(topK)
            );
            // 执行搜索
            SearchResponse<ChunkDocument> response = esClient.search(searchRequest, ChunkDocument.class);
            List<Chunk> chunks = hitsToChunks(response.hits().hits());
            log.debug("Metadata recall [{}] returned {} parent chunks for query: {}", name(), chunks.size(), query);
            return chunks;
        } catch (Exception e) {
            log.error("Metadata recall failed", e);
            return List.of();
        }
    }

    private Query buildPermissionFilter(java.util.Map<String, Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        String role = String.valueOf(filters.getOrDefault("role", "USER"));
        if ("ADMIN".equals(role) && Boolean.TRUE.equals(filters.get("includeDisabled"))) {
            return null;
        }
        String department = String.valueOf(filters.getOrDefault("department", ""));
        return Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field("documentStatus").value("READY")))
                .mustNot(f -> f.term(t -> t.field("disabled").value(true)))
                .filter(f3 -> f3.bool(permission -> permission
                        .should(s -> s.term(t -> t.field("visibility").value("COMPANY")))
                        .should(s -> s.bool(bb -> bb.mustNot(m -> m.exists(e -> e.field("visibility")))))
                        .should(s -> s.bool(bb -> bb
                                .filter(fa -> fa.term(t -> t.field("visibility").value("DEPARTMENT")))
                                .filter(fb -> fb.term(t -> t.field("department").value(department)))
                        ))
                        .minimumShouldMatch("1")
                ))
        ));
    }

    // 将搜索结果转换为 Chunk 列表
    private List<Chunk> hitsToChunks(List<Hit<ChunkDocument>> hits) {
        List<Chunk> chunks = new ArrayList<>();
        for (Hit<ChunkDocument> hit : hits) {
            if (hit.source() == null) continue;
            // 从搜索结果中提取文档
            ChunkDocument doc = hit.source();
            Chunk chunk = new Chunk();
            chunk.setId(doc.getId());
            chunk.setDocumentId(doc.getDocumentId());
            chunk.setParentChunkId(doc.getParentChunkId());
            chunk.setContentType(Chunk.ContentType.valueOf(doc.getContentType()));
            chunk.setContent(doc.getContent());
            chunk.setChunkIndex(doc.getChunkIndex());
            chunk.setStartOffset(doc.getStartOffset());
            chunk.setEndOffset(doc.getEndOffset());
            if (doc.getCreatedAt() != null) {
                chunk.setCreatedAt(Instant.parse(doc.getCreatedAt()));
            }
            if (doc.getMetadata() != null) {
                chunk.getMetadata().putAll(doc.getMetadata());
            }
            if (hit.score() != null) {
                chunk.addMetadata("_score", hit.score());
            }
            chunks.add(chunk);
        }
        return chunks;
    }
}

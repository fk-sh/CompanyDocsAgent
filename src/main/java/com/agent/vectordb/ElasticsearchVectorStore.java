package com.agent.vectordb;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.agent.core.Chunk;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ES 向量存储层，封装对 {@code agent_chunks} 索引的 CRUD 和向量检索操作。
 * <p>
 * 提供两种检索模式，为 Phase 6 的多路召回打基础：
 * <ul>
 *   <li>{@link #knnSearch(float[], int)} — 向量近邻检索（KNN），基于余弦相似度</li>
 *   <li>{@link #bm25Search(String, int)} — 关键词检索（BM25），基于 standard 分词器</li>
 * </ul>
 * <p>
 * 写入支持单条 {@link #upsert(Chunk)} 和批量 {@link #upsertBatch(List)} 两种模式，
 * 批量写入使用 ES Bulk API 提升吞吐。
 * <p>
 * Bean 由 {@link VectorDbAutoConfiguration} 条件装配创建，非直接 {@code @Component}。
 */
@Slf4j
public class ElasticsearchVectorStore {

    private final ElasticsearchClient esClient;

    public ElasticsearchVectorStore(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    /**
     * 单条写入或更新一个 Chunk 到 ES 索引。
     */
    public void upsert(Chunk chunk) {
        try {
            IndexRequest<ChunkDocument> request = IndexRequest.of(i -> i
                    .index(EsIndexInitializer.CHUNKS_INDEX)
                    .id(chunk.getId())
                    .document(toChunkDocument(chunk))
            );
            esClient.index(request);
            log.debug("Upserted chunk {} to ES", chunk.getId());
        } catch (IOException e) {
            log.error("Failed to upsert chunk {}", chunk.getId(), e);
            throw new RuntimeException("Failed to upsert chunk to ES", e);
        }
    }

    /**
     * 批量写入 Chunk 到 ES，使用 Bulk API。
     *
     * @throws RuntimeException 当任意一条写入失败时
     */
    public void upsertBatch(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (Chunk chunk : chunks) {
                bulkBuilder.operations(op -> op
                        .index(idx -> idx
                                .index(EsIndexInitializer.CHUNKS_INDEX)
                                .id(chunk.getId())
                                .document(toChunkDocument(chunk))
                        )
                );
            }

            BulkResponse response = esClient.bulk(bulkBuilder
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)
                    .build());
            if (response.errors()) {
                List<String> errorMessages = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> "id=" + item.id() + " error=" + item.error().reason())
                        .collect(Collectors.toList());
                log.error("Bulk upsert had errors: {}", errorMessages);
                throw new RuntimeException("Bulk upsert to ES failed: " + errorMessages);
            }

            log.info("Bulk upserted {} chunks to ES", chunks.size());
        } catch (IOException e) {
            log.error("Failed to bulk upsert {} chunks", chunks.size(), e);
            throw new RuntimeException("Failed to bulk upsert chunks to ES", e);
        }
    }

    /**
     * KNN 向量近邻检索（无过滤条件）。
     *
     * @param queryVector 查询向量（由 bge-large-zh-v1.5 生成，1024 维）
     * @param k           返回 Top-K 结果数
     * @return 按相似度降序排列的 Chunk 列表
     */
    public List<Chunk> knnSearch(float[] queryVector, int k) {
        return knnSearch(queryVector, k, null);
    }

    /**
     * KNN 向量近邻检索（带内容类型过滤）。
     *
     * @param queryVector        查询向量
     * @param k                  返回 Top-K 结果数
     * @param contentTypeFilter  按 {@code contentType} 过滤（如 {@code "CODE"}），为 {@code null} 时不过滤
     * @return 按相似度降序排列的 Chunk 列表
     */
    public List<Chunk> knnSearch(float[] queryVector, int k, String contentTypeFilter) {
        try {
            KnnSearch knn = KnnSearch.of(kq -> {
                KnnSearch.Builder builder = kq
                        .field("embedding")
                        .queryVector(toFloatList(queryVector))
                        .k(k)
                        .numCandidates(k * 3);

                if (contentTypeFilter != null && !contentTypeFilter.isBlank()) {
                    builder.filter(Query.of(q -> q
                            .term(t -> t
                                    .field("contentType")
                                    .value(contentTypeFilter)
                            )
                    ));
                }
                return builder;
            });

            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(EsIndexInitializer.CHUNKS_INDEX)
                    .knn(knn)
                    .source(src -> src.filter(f -> f.excludes("createdAt")))
                    .size(k)
            );

            SearchResponse<ChunkDocument> response = esClient.search(searchRequest, ChunkDocument.class);
            List<Chunk> chunks = hitsToChunks(response.hits().hits());
            log.info("KNN search: returned {} hits", chunks.size());
            return chunks;
        } catch (IOException e) {
            log.error("KNN search failed", e);
            throw new RuntimeException("KNN search failed", e);
        }
    }

    /**
     * BM25 关键词检索，用于多路召回的 keyword 通道路。
     *
     * @param queryText 查询文本
     * @param k         返回 Top-K 结果数
     * @return 按 BM25 得分降序排列的 Chunk 列表
     */
    public List<Chunk> bm25Search(String queryText, int k) {
        try {
            SearchRequest searchRequest = SearchRequest.of(s -> s
                    .index(EsIndexInitializer.CHUNKS_INDEX)
                    .query(q -> q.bool(b -> b
                            .should(s1 -> s1.matchPhrase(mp -> mp
                                    .field("content")
                                    .query(queryText)
                                    .boost(3.0F)
                            ))
                            .should(s2 -> s2.match(m -> m
                                    .field("content")
                                    .query(queryText)
                                    .boost(1.0F)
                            ))
                            .minimumShouldMatch("1")
                    ))
                    .source(src -> src.filter(f -> f.excludes("createdAt")))
                    .size(k)
            );

            SearchResponse<ChunkDocument> response = esClient.search(searchRequest, ChunkDocument.class);
            List<Chunk> result = hitsToChunks(response.hits().hits());
            log.info("BM25 search: query='{}', hits={}", queryText, result.size());
            return result;
        } catch (IOException e) {
            log.error("BM25 search failed", e);
            throw new RuntimeException("BM25 search failed", e);
        }
    }

    /**
     * 按文档 ID 删除该文档下的所有 Chunk。
     */
    public void deleteByDocumentId(String documentId) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(d -> d
                    .index(EsIndexInitializer.CHUNKS_INDEX)
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed)// 忽略冲突，继续删除
                                       .refresh(true)// 立即刷新索引，确保删除生效
                    .query(q -> q// 删除 documentId 字段等于指定值的文档
                            .term(t -> t// 匹配 documentId 字段等于指定值的文档
                                    .field("documentId")// 匹配 documentId 字段
                                    .value(documentId)// 匹配 documentId 字段等于指定值的文档
                            )
                    )
            );
            DeleteByQueryResponse response = esClient.deleteByQuery(request);// 删除文档
            log.info("Deleted {} chunks for document {}", response.deleted(), documentId);
        } catch (IOException e) {
            log.error("Failed to delete chunks for document {}", documentId, e);
            throw new RuntimeException("Failed to delete chunks from ES", e);
        }
    }

    /**
     * 统计某文档在 ES 中的 Chunk 数。
     */
    public long countByDocumentId(String documentId) {
        try {
            CountRequest countRequest = CountRequest.of(c -> c
                    .index(EsIndexInitializer.CHUNKS_INDEX)
                    .query(q -> q
                            .term(t -> t
                                    .field("documentId")
                                    .value(documentId)
                            )
                    )
            );
            return esClient.count(countRequest).count();
        } catch (IOException e) {
            log.error("Failed to count chunks for document {}", documentId, e);
            return 0;
        }
    }

    /**
     * 将 ES 搜索结果转为 {@link Chunk} 列表，同时将 {@code _score} 写入 metadata。
     */
    @SuppressWarnings("unchecked")
    private List<Chunk> hitsToChunks(List<Hit<ChunkDocument>> hits) {
        List<Chunk> chunks = new ArrayList<>();
        // 遍历搜索结果，将文档转换为 Chunk 列表
        for (Hit<ChunkDocument> hit : hits) {
            if (hit.source() == null) continue;

            // 从搜索结果中提取文档
            ChunkDocument doc = hit.source();
            Chunk chunk = new Chunk();
            // 从文档中提取 Chunk 相关信息
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
                doc.getMetadata().forEach((k, v) -> chunk.addMetadata(k, v));
            }
            if (hit.score() != null) {
                chunk.addMetadata("_score", hit.score());
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    /**
     * 将 {@link Chunk} 转为 ES 索引文档 {@link ChunkDocument}。
     * embedding 从 {@code float[]} 转为 {@code List<Float>} 以满足 ES dense_vector 类型。
     */
    private ChunkDocument toChunkDocument(Chunk chunk) {
        ChunkDocument doc = new ChunkDocument();
        doc.setId(chunk.getId());
        doc.setDocumentId(chunk.getDocumentId());
        doc.setParentChunkId(chunk.getParentChunkId());
        doc.setContentType(chunk.getContentType() != null ? chunk.getContentType().name() : "TEXT");
        doc.setContent(chunk.getContent());
        doc.setChunkIndex(chunk.getChunkIndex());
        doc.setStartOffset(chunk.getStartOffset());
        doc.setEndOffset(chunk.getEndOffset());
        doc.setEmbedding(chunk.getEmbedding() != null ? toFloatList(chunk.getEmbedding()) : null);
        doc.setMetadata(chunk.getMetadata());
        doc.setCreatedAt(chunk.getCreatedAt() != null ? chunk.getCreatedAt().toString() : Instant.now().toString());
        return doc;
    }

    public ElasticsearchClient getEsClient() {
        return esClient;
    }

    /**
     * 将 {@code float[]} 转为 {@code List<Float>}，适配 ES Java Client 的 dense_vector 类型。
     */
    private static List<Float> toFloatList(float[] floats) {
        List<Float> list = new ArrayList<>(floats.length);
        for (float f : floats) {
            list.add(f);
        }
        return list;
    }
}

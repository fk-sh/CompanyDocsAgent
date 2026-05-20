package com.agent.retrieval;

import com.agent.core.Chunk;
import com.agent.vectordb.ElasticsearchVectorStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ParentChildResolver {

    private final ElasticsearchVectorStore vectorStore;

    public ParentChildResolver(ElasticsearchVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<Chunk> resolve(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        Set<String> parentIds = new HashSet<>();
        List<Chunk> itemsWithoutParent = new ArrayList<>();

        for (Chunk chunk : chunks) {
            if (chunk.hasParent()) {
                parentIds.add(chunk.getParentChunkId());// 添加父文档 ID 到集合
            } else {
                itemsWithoutParent.add(chunk);// 如果无父文档，添加到无父文档列表，因为它们自身就是根文档，即父文档
            }
        }

        if (parentIds.isEmpty()) {
            return chunks;
        }

        Map<String, Chunk> fetchedParents = fetchParentChunks(parentIds);// 使用父文档 ID 从向量数据库中获取父文档

        Set<String> seenIds = new HashSet<>();// 用于记录已处理的文档 ID
        // 用于存储最终的已解析文档列表
        List<Chunk> resolved = new ArrayList<>();
        // 处理已获取的父文档
        for (Chunk fetched : fetchedParents.values()) {
            if (seenIds.add(fetched.getId())) {
                resolved.add(fetched);
            }
        }

        // 处理无父文档的文档
        // 处理孤儿文档，确保它们也被包含在最终结果中
        for (Chunk chunk : itemsWithoutParent) {
            if (seenIds.add(chunk.getId())) {
                resolved.add(chunk);
            }
        }

        log.debug("ParentChildResolver: {} chunks -> {} resolved ({} parents fetched, {} orphaned)",
                chunks.size(), resolved.size(), fetchedParents.size(),
                itemsWithoutParent.size());
        return resolved;// 返回已解析的文档列表
    }

    /**
     * 从向量数据库中获取父文档。
     * 
     * @param parentIds 父文档 ID 集合
     * @return 父文档映射，键为父文档 ID，值为父文档
     */
    private Map<String, Chunk> fetchParentChunks(Set<String> parentIds) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }

        try {
            co.elastic.clients.elasticsearch.ElasticsearchClient esClient =
                    vectorStore.getEsClient();// 获取 Elasticsearch 客户端

            // 构建搜索请求
            co.elastic.clients.elasticsearch.core.SearchRequest searchRequest =
                    co.elastic.clients.elasticsearch.core.SearchRequest.of(s -> s
                            .index(com.agent.vectordb.EsIndexInitializer.CHUNKS_INDEX)
                            .query(q -> q.ids(ids -> ids.values(new ArrayList<>(parentIds))))// 构建查询，根据父文档 ID 列表
                            .size(parentIds.size())// 设置搜索大小为父文档 ID 数量
                    );

            // 执行搜索请求
            co.elastic.clients.elasticsearch.core.SearchResponse<com.agent.vectordb.ChunkDocument> response =
                    esClient.search(searchRequest, com.agent.vectordb.ChunkDocument.class);

            Map<String, Chunk> result = new HashMap<>();
            // 处理搜索结果
            for (co.elastic.clients.elasticsearch.core.search.Hit<com.agent.vectordb.ChunkDocument> hit :
                    response.hits().hits()) {
                if (hit.source() == null) continue;

                com.agent.vectordb.ChunkDocument doc = hit.source();// 获取文档内容
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
                    chunk.setCreatedAt(java.time.Instant.parse(doc.getCreatedAt()));
                }
                if (doc.getMetadata() != null) {
                    doc.getMetadata().forEach((k, v) -> chunk.addMetadata(k, v));
                }

                result.put(doc.getId(), chunk);
            }

            log.debug("Fetched {} parent chunks out of {} requested", result.size(), parentIds.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch parent chunks", e);
            return Map.of();
        }
    }
}

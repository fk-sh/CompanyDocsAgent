package com.agent.retrieval;

import com.agent.core.Chunk;
import com.agent.core.RecallStrategy;
import com.agent.core.Retriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Component
public class HybridRetriever implements Retriever {

    static final int RRF_K = 60;// RRF 算法参数
    static final int RECALL_TOP_K = 30;// 回调策略参数
    static final int RRF_POOL_SIZE = 100;// RRF 算法参数
    static final int COARSE_TOP_K = 50;// 粗粒度排序参数
    static final int FINE_TOP_K = 10;// 细粒度排序参数

    private final List<RecallStrategy> recallStrategies;// 回调策略列表
    private final QueryRewriterImpl queryRewriter;// 查询重写器与多query重写器
    private final CoarseRanker coarseRanker;// 粗粒度排序器
    private final FineRanker fineRanker;// 细粒度排序器
    private final ParentChildResolver parentChildResolver;// 父子文档解析器
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();// 异步任务执行器

    public HybridRetriever(List<RecallStrategy> recallStrategies,
                           QueryRewriterImpl queryRewriter,
                           CoarseRanker coarseRanker,
                           FineRanker fineRanker,
                           ParentChildResolver parentChildResolver) {
        this.recallStrategies = recallStrategies;
        this.queryRewriter = queryRewriter;
        this.coarseRanker = coarseRanker;
        this.fineRanker = fineRanker;
        this.parentChildResolver = parentChildResolver;
    }

    @Override
    public List<Chunk> retrieve(String query, int topK) {
        return retrieve(query, topK, Map.of());
    }

    @Override
    public List<Chunk> retrieve(String query, int topK, Map<String, Object> filters) {
        log.info("HybridRetriever: query='{}', topK={}, filters={}", query, topK, filters);

        List<String> allQueries = queryRewriter.expandAndMerge(query);// 多query重写器扩展查询
        List<RankedChunk> allCandidates;// 所有候选文档

        if (allQueries.size() == 1) {
            allCandidates = multiRecallSingleQuery(allQueries.get(0));// 单查询多策略召回
        } else {
            log.info("Query expanded to {} variants: {}", allQueries.size(), allQueries);
            allCandidates = multiRecallMultiQuery(allQueries);// 多查询多策略召回文档
        }

        List<RankedChunk> rrfRanked = rrfFusion(allCandidates);// RRF 融合所有召回的文档

        List<RankedChunk> coarseRanked = coarseRanker.rank(query, rrfRanked, COARSE_TOP_K);// 粗粒度排序

        List<RankedChunk> fineRanked = fineRanker.rerank(query, coarseRanked, Math.min(topK * 2, FINE_TOP_K));// 细粒度排序

        // 父子文档解析器解析文档
        List<Chunk> resolved = parentChildResolver.resolve(
                fineRanked.stream()// 精简细粒度排序结果
                .map(RankedChunk::chunk)// 提取文档片段
                .toList()
        );

        int resultSize = Math.min(topK, resolved.size());
        List<Chunk> finalResults = resolved.subList(0, resultSize);// 取 topK 个文档

        log.info("HybridRetriever completed: {} results returned out of {} resolved chunks",
                finalResults.size(), resolved.size());
        return finalResults;// 返回 topK 个文档
    }

    // 单查询多策略召回
    private List<RankedChunk> multiRecallSingleQuery(String query) {

        log.info("MultiRecallSingleQuery: query='{}'", query);
        List<RankedChunk> allChunks = new ArrayList<>();
        List<CompletableFuture<List<Chunk>>> futures = new ArrayList<>();// 异步任务列表

        //实现并行召回策略，三条路同时执行召回，不需要一个一个执行，节省了时间，提高了召回效率
        for (RecallStrategy strategy : recallStrategies) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return strategy.recall(query, RECALL_TOP_K);//返回召回结果，其实就是存储在每一个虚拟线程的futures中
                } catch (Exception e) {
                    log.warn("Recall strategy {} failed: {}", strategy.name(), e.getMessage());
                    return List.<Chunk>of();// 返回空列表
                }
            }, executor));
        }

        // 等待所有异步任务完成，现在获取所有虚拟线程存储的结果
        for (int i = 0; i < recallStrategies.size() && i < futures.size(); i++) {
            try {
                List<Chunk> results = futures.get(i).join();// 等待异步任务完成，现在获取每个虚拟线程存储的结果
                String strategyName = recallStrategies.get(i).name();// 获取当前召回策略的名称
                for (int rank = 0; rank < results.size(); rank++) {// 遍历每个召回结果
                    Chunk chunk = results.get(rank);// 获取当前召回结果
                    if (chunk == null) {
                        continue;// 跳过空结果
                    }
                    RankedChunk rc = new RankedChunk(chunk);
                    if (strategyName.contains("bm25")) {
                        rc.setBm25Score(toScore(chunk));
                    } else if (strategyName.contains("knn") || strategyName.contains("vector")) {
                        rc.setKnnScore(toScore(chunk));
                    }
                    rc.chunk().addMetadata("recall_rank_" + strategyName, rank + 1);
                    allChunks.add(rc);// 添加到所有候选文档列表
                }
            } catch (Exception e) {
                log.warn("Recall strategy {} join failed: {}", recallStrategies.get(i).name(), e.getMessage());
            }
        }

        return allChunks;// 返回所有候选文档列表
    }

    // 多查询多策略召回
    private List<RankedChunk> multiRecallMultiQuery(List<String> queries) {
        Map<String, RankedChunk> mergedMap = new LinkedHashMap<>();

        for (String q : queries) {
            List<RankedChunk> results = multiRecallSingleQuery(q);// 单查询多策略召回
            for (RankedChunk rc : results) {// 遍历每个召回结果
                mergedMap.merge(rc.chunk().getId(), rc, (existing, incoming) -> {
                    existing.setBm25Score(Math.max(existing.bm25Score(), incoming.bm25Score()));
                    existing.setKnnScore(Math.max(existing.knnScore(), incoming.knnScore()));
                    return existing;
                });
            }
        }

        return new ArrayList<>(mergedMap.values());
    }

    // RRF 融合所有召回的文档
    List<RankedChunk> rrfFusion(List<RankedChunk> allCandidates) {
        Map<String, RankedChunk> chunkMap = new LinkedHashMap<>();

        for (RankedChunk rc : allCandidates) {
            chunkMap.putIfAbsent(rc.chunk().getId(), rc);// 添加到映射中，同时确保没有重复的文档
        }

        Map<String, List<Integer>> recallRanksByStrategy = new LinkedHashMap<>();// 每个召回策略的召回排名

        //判断是否被多路命中，如果是，就将它的召回排名添加到列表中
        for (RecallStrategy strategy : recallStrategies) {
            String name = strategy.name();// 获取当前召回策略的名称
            List<RankedChunk> strategyResults = allCandidates.stream()
                    .filter(rc -> rc.chunk().getMetadata().containsKey("recall_rank_" + name))// 过滤出当前召回策略的召回结果
                    .sorted(Comparator.comparingInt(rc ->
                            ((Number) rc.chunk().getMetadata().get("recall_rank_" + name)).intValue()))// 按召回排名排序
                    .toList();// 转换为列表

            for (int i = 0; i < strategyResults.size(); i++) {
                String id = strategyResults.get(i).chunk().getId();// 获取当前召回结果的 ID
                recallRanksByStrategy.computeIfAbsent(id, k -> new ArrayList<>()).add(i + 1);// 添加到映射中，同时确保没有重复的文档，将当前召回结果的排名添加到列表中
            }
        }

        List<Map.Entry<String, RankedChunk>> entries = new ArrayList<>();
        for (Map.Entry<String, RankedChunk> entry : chunkMap.entrySet()) {
            String id = entry.getKey();
            RankedChunk rc = entry.getValue();

            double rrfScore = 0.0;
            List<Integer> ranks = recallRanksByStrategy.getOrDefault(id, List.of());
            for (int rank : ranks) {
                rrfScore += 1.0 / (RRF_K + rank);// 计算当前召回结果的 RRF 分数
            }
            rc.setRrfScore(rrfScore);// 设置当前召回结果的 RRF 分数
            entries.add(entry);// 添加到列表中
        }

        entries.sort((a, b) -> Double.compare(b.getValue().rrfScore(), a.getValue().rrfScore()));// 按 RRF 分数排序 

        int poolSize = Math.min(RRF_POOL_SIZE, entries.size());
        List<RankedChunk> pooled = new ArrayList<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pooled.add(entries.get(i).getValue());
        }

        log.debug("RRF fusion: {} candidates -> {} pooled (top RRF score: {})",
                allCandidates.size(), pooled.size(),
                pooled.isEmpty() ? 0 : pooled.get(0).rrfScore());
        return pooled;// 返回融合后的文档列表
    }

    private static double toScore(Chunk chunk) {
        Object score = chunk.getMetadata().get("_score");
        if (score instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }
}

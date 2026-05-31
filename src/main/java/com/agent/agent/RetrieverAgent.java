package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Chunk;
import com.agent.llm.DeepSeekChatClient;
import com.agent.retrieval.HybridRetriever;
import com.agent.retrieval.QueryRewriterImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component("retrieverAgent")
public class RetrieverAgent implements Agent {

    private static final int MIN_CHUNKS = 5;
    private static final int MAX_REACT_LOOPS = 2;

    private static final String REACT_PROMPT = """
            你是一个检索结果分析助手。以下是从知识库检索到的文档内容，请判断是否足以回答用户问题。
            如果不足，请生成一个新的检索查询以补充缺失的信息。

            【用户问题】
            %s

            【当前检索到的文档内容】
            %s

            请分析：
            1. 当前检索结果是否足够？如果足够，回复 SUFFICIENT
            2. 如果不足，请指出缺失了哪方面的信息，并回复新的检索查询（只输出一个查询语句）

            回复格式：
            SUFFICIENT
            或：直接输出新的检索查询（一行）
            """;

    private final HybridRetriever hybridRetriever;
    private final QueryRewriterImpl queryRewriter;
    private final DeepSeekChatClient llm;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public RetrieverAgent(HybridRetriever hybridRetriever, QueryRewriterImpl queryRewriter,
                          DeepSeekChatClient llm) {
        this.hybridRetriever = hybridRetriever;
        this.queryRewriter = queryRewriter;
        this.llm = llm;
    }

    @Override
    public String name() {
        return "retriever";
    }

    public String retrieve(AgentContext ctx, List<String> queries) {
        return retrieve(queries, buildFilters(ctx));
    }

    public String retrieve(List<String> queries) {
        return retrieve(queries, Map.of());
    }

    public String retrieve(List<String> queries, Map<String, Object> filters) {
        if (queries == null || queries.isEmpty()) {
            return "";
        }

        ConcurrentLinkedQueue<Chunk> allChunks = new ConcurrentLinkedQueue<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String query : queries) {
            futures.add(CompletableFuture.runAsync(() -> {
                List<Chunk> roundChunks = retrieveWithPipeline(query, filters);
                allChunks.addAll(roundChunks);
            }, executor));
        }

        for (CompletableFuture<Void> future : futures) {
            try {
                future.join();
            } catch (Exception e) {
                log.warn("RetrieverAgent: query retrieval failed: {}", e.getMessage());
            }
        }

        Set<String> seen = new java.util.HashSet<>();
        List<Chunk> deduplicated = new ArrayList<>();
        for (Chunk c : allChunks) {
            if (seen.add(c.getId())) {
                deduplicated.add(c);
            }
        }

        log.info("RetrieverAgent initial retrieval: {} chunks from {} queries", deduplicated.size(), queries.size());

        int reactLoop = 0;
        while (deduplicated.size() < MIN_CHUNKS && reactLoop < MAX_REACT_LOOPS) {
            reactLoop++;
            log.info("RetrieverAgent ReAct loop {}/{}: only {} chunks (need {})",
                    reactLoop, MAX_REACT_LOOPS, deduplicated.size(), MIN_CHUNKS);

            String newQuery = generateReActQuery(queries.get(0), deduplicated);
            if (newQuery == null || newQuery.isEmpty()) {
                log.info("RetrieverAgent ReAct: LLM thinks results are sufficient");
                break;
            }

            List<Chunk> moreChunks = retrieveWithPipeline(newQuery, filters);
            for (Chunk c : moreChunks) {
                if (seen.add(c.getId())) {
                    deduplicated.add(c);
                }
            }
            log.info("RetrieverAgent ReAct loop {}: added {} more chunks, total={}",
                    reactLoop, moreChunks.size(), deduplicated.size());
        }

        return buildContextText(deduplicated);
    }

    private List<Chunk> retrieveWithPipeline(String query, Map<String, Object> filters) {
        var rewritten = queryRewriter.rewrite(query);

        List<String> variants = new ArrayList<>();
        variants.add(query);
        variants.addAll(rewritten.getEffectiveQueries());

        List<String> rewrittenQueries = rewritten.getEffectiveQueries();
        List<CompletableFuture<List<String>>> expandFutures = new ArrayList<>();
        for (String q : rewrittenQueries) {
            expandFutures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return queryRewriter.expand(q);
                } catch (Exception e) {
                    log.warn("RetrieverAgent: expand '{}' failed: {}", q, e.getMessage());
                    return List.<String>of();
                }
            }, executor));
        }

        for (CompletableFuture<List<String>> future : expandFutures) {
            try {
                List<String> expanded = future.join();
                variants.addAll(expanded);
            } catch (Exception e) {
                log.warn("RetrieverAgent: expand future failed: {}", e.getMessage());
            }
        }

        List<String> uniqueVariants = new ArrayList<>(new java.util.LinkedHashSet<>(variants));
        log.debug("RetrieverAgent: query expanded to {} variants", uniqueVariants.size());

        return hybridRetriever.retrieveWithQueries(query, uniqueVariants, 10, filters);
    }

    private Map<String, Object> buildFilters(AgentContext ctx) {
        return Map.of(
                "userId", ctx.getVariable("userId", ""),
                "department", ctx.getVariable("department", ""),
                "role", ctx.getVariable("role", "USER")
        );
    }

    private String generateReActQuery(String originalQuery, List<Chunk> currentChunks) {
        String contextText = buildContextText(currentChunks);
        String prompt = String.format(REACT_PROMPT, originalQuery, contextText);

        try {
            String response = llm.chat(prompt).trim();
            if (response.equalsIgnoreCase("SUFFICIENT") || response.isEmpty()) {
                return null;
            }
            log.debug("RetrieverAgent ReAct: new query='{}'", response);
            return response;
        } catch (Exception e) {
            log.warn("RetrieverAgent ReAct: LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildContextText(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return "（无检索结果）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            String fileName = (String) chunk.getMetadata().getOrDefault("fileName", "未知文档");
            sb.append("\n\n[来源 ").append(i + 1).append(": ").append(fileName).append("]\n");
            sb.append(chunk.getContent());
            sb.append("\n\n---\n\n");
        }
        return sb.toString();
    }

    @Override
    public String execute(AgentContext ctx) {
        return "retriever agent executed";
    }
}

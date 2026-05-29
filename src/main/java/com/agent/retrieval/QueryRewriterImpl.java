package com.agent.retrieval;

import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class QueryRewriterImpl {

    private static final String REWRITE_PROMPT = """
            你是一个查询改写助手。你的任务是将用户原始查询优化为更适合检索系统使用的形式。

            规则：
            1. 如果用户问题较短或口语化，请补充背景、替换同义词，使其更精确
            2. 如果用户问题包含多个子问题，将其拆解为多个独立子查询
            3. 对缩写或专业术语进行展开说明

            输出格式要求：
            - 如果只需要改写：回复以 REWRITE: 开头，后跟改写后的查询
            - 如果需要拆解：回复以 DECOMPOSE: 开头，每行一个子查询，子查询以 - 开头
            - 如果原查询已经足够好：回复以 KEEP: 开头，后跟原查询
            """;

    private static final String EXPAND_PROMPT = """
            你是一个查询扩展助手。给定一个用户查询，请生成 3 个从不同角度表述的等价查询版本，
            用于覆盖更多表述差异，提高检索召回率。

            角度要求：
            - 角度1：使用同义词/近义词替换关键词
            - 角度2：改变句式结构（如疑问句→陈述句，或反过来）
            - 角度3：使用不同的术语体系或专业表达

            输出格式：每行一个扩展查询，以 - 开头，不要输出其他任何内容。
            示例输出：
            - Java应用性能调优的最佳实践
            - 如何提升JVM程序的运行效率
            - Java服务端性能优化方法论
            """;

    private static final int MAX_EXPANDED_QUERIES = 3;// 最大扩展查询数量

    private final DeepSeekChatClient chatClient;
    private final ExecutorService expandExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public QueryRewriterImpl(DeepSeekChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public RewriteResult rewrite(String originalQuery) {
        log.debug("Rewriting query: {}", originalQuery);

        try {
            String response = chatClient.chat(REWRITE_PROMPT, originalQuery);
            return parseRewriteResponse(response, originalQuery);//返回改写后的查询语句
        } catch (Exception e) {
            log.warn("Query rewrite failed, using original query: {}", e.getMessage());
            return RewriteResult.keep(originalQuery);//遇到错误，进行降级处理，返回原查询
        }
    }

    //多query改写
    public List<String> expand(String originalQuery) {
        log.debug("Expanding query: {}", originalQuery);

        try {
            String response = chatClient.chat(EXPAND_PROMPT, originalQuery);
            List<String> expanded = extractSubQueries(response);
            if (expanded.isEmpty()) {
                log.debug("Query expansion returned empty, using original query only");
                return List.of();
            }
            // 取前 MAX_EXPANDED_QUERIES 个扩展查询，不取全部
            List<String> trimmed = expanded.stream()
                    .limit(MAX_EXPANDED_QUERIES)
                    .toList();
            log.debug("Query expanded into {} variants: {}", trimmed.size(), trimmed);
            return trimmed;//返回扩展后的查询语句
        } catch (Exception e) {
            log.warn("Query expansion failed: {}", e.getMessage());
            return List.of();//遇到错误，返回空列表
        }
    }

    //多query改写合并
    public List<String> expandAndMerge(String originalQuery) {
        Set<String> allQueries = new LinkedHashSet<>();
        allQueries.add(originalQuery);

        RewriteResult rewriteResult = rewrite(originalQuery);
        List<String> rewritten = rewriteResult.getEffectiveQueries();
        allQueries.addAll(rewritten);

        List<CompletableFuture<List<String>>> expandFutures = new ArrayList<>();
        for (String q : rewritten) {
            expandFutures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return expand(q);
                } catch (Exception e) {
                    log.warn("Expand query '{}' failed: {}", q, e.getMessage());
                    return List.<String>of();
                }
            }, expandExecutor));
        }

        for (CompletableFuture<List<String>> future : expandFutures) {
            try {
                List<String> expanded = future.join();
                allQueries.addAll(expanded);
            } catch (Exception e) {
                log.warn("Expand future join failed: {}", e.getMessage());
            }
        }

        return new ArrayList<>(allQueries);
    }

    //解析改写响应
    private RewriteResult parseRewriteResponse(String response, String originalQuery) {
        if (response == null || response.isBlank()) {
            return RewriteResult.keep(originalQuery);//遇到错误，返回原查询
        }

        String trimmed = response.trim();//去掉首尾空格

        //DECOMPOSE分支：拆解查询
        if (trimmed.startsWith("DECOMPOSE:")) {
            List<String> subQueries = extractSubQueries(trimmed);
            if (subQueries.isEmpty()) {
                return RewriteResult.keep(originalQuery);
            }
            log.debug("Query decomposed into {} sub-queries: {}", subQueries.size(), subQueries);
            return RewriteResult.decompose(subQueries);
        }

        //REWRITE分支：改写查询
        if (trimmed.startsWith("REWRITE:")) {
            String rewritten = trimmed.substring("REWRITE:".length()).trim();
            if (rewritten.isEmpty()) {
                return RewriteResult.keep(originalQuery);
            }
            log.debug("Query rewritten: {} -> {}", originalQuery, rewritten);
            return RewriteResult.rewrite(rewritten);
        }

        //KEEP分支：保持原查询
        if (trimmed.startsWith("KEEP:")) {
            return RewriteResult.keep(originalQuery);
        }

        return RewriteResult.keep(originalQuery);//遇到未知指令，保持原查询
    }

    //从DECOMPOSE响应中提取子查询
    private List<String> extractSubQueries(String decomponseResponse) {
        List<String> subQueries = new ArrayList<>();
        Pattern pattern = Pattern.compile("^\\s*-\\s*(.+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(decomponseResponse);
        // 遍历所有匹配项，提取子查询
        while (matcher.find()) {
            String sq = matcher.group(1).trim();
            if (!sq.isEmpty()) {
                subQueries.add(sq);
            }
        }
        return subQueries;
    }

    /**
     * RewriteResult 是一个 record，自动生成了构造器、equals/hashCode、toString 以及字段的访问器方法。
     * 包含三个字段：
     * 
     * rewrittenQuery：改写后的单个查询字符串（当类型为 KEEP 或 REWRITE 时有效）。
     * 
     * subQueries：分解后的多个子查询列表（当类型为 DECOMPOSE 时有效）。
     * 
     * type：当前结果类型，取自内部枚举 Type。
     */
    public record RewriteResult(String rewrittenQuery, List<String> subQueries, Type type) {
        public enum Type { KEEP, REWRITE, DECOMPOSE }

        public static RewriteResult keep(String query) {
            return new RewriteResult(query, List.of(), Type.KEEP);
        }

        public static RewriteResult rewrite(String rewritten) {
            return new RewriteResult(rewritten, List.of(), Type.REWRITE);
        }

        public static RewriteResult decompose(List<String> subQueries) {
            return new RewriteResult(null, subQueries, Type.DECOMPOSE);
        }

        public List<String> getEffectiveQueries() {
            return switch (type) {
                case KEEP, REWRITE -> List.of(rewrittenQuery);
                case DECOMPOSE -> subQueries;
            };
        }
    }
}

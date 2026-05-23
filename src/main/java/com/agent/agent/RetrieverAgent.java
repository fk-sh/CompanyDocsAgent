package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Chunk;
import com.agent.retrieval.HybridRetriever;
import com.agent.retrieval.QueryRewriterImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索召回 Agent，执行 Query 改写 → 多路召回 → 粗排 → 精排 的完整检索链路。
 * <p>
 * 执行模式：支持 ReAct 循环，LLM 可自主判断检索结果是否充分，
 * 不足时自动改写 Query 重新检索，最多循环 3 次。
 * <p>
 * 输入（ctx 读取）：subQueries
 * 输出（ctx 写入）：documents, retrievedContext
 */
@Slf4j
@Component("retrieverAgent")
public class RetrieverAgent implements Agent {

    private static final int MAX_REACT_LOOPS = 3;//最大循环次数

    private final HybridRetriever hybridRetriever;
    private final QueryRewriterImpl queryRewriter;

    public RetrieverAgent(HybridRetriever hybridRetriever, QueryRewriterImpl queryRewriter) {
        this.hybridRetriever = hybridRetriever;
        this.queryRewriter = queryRewriter;
    }

    @Override
    public String name() {
        return "retriever";
    }

    /**
     * 执行检索召回链路。
     * <p>
     * 从 ctx 中获取子任务查询语句，递归执行 ReAct 循环，直到检索到足够多的文档或超过最大循环次数。
     * <p>
     * 最终返回所有子任务的文档合并结果。
     */
    @Override
    public String execute(AgentContext ctx) {
        List<String> subQueries = ctx.getVariable("subQueries");//从ctx中获取子任务查询语句
        if (subQueries == null || subQueries.isEmpty()) {
            log.warn("RetrieverAgent: no subQueries found, using userQuery as fallback");
            String fallback = ctx.getUserQuery();//用 ctx 对象的 userQuery 字段
            if (fallback == null || fallback.isEmpty()) {
                fallback = ctx.getVariable("userQuery");// 第三优先级：从 variables Map 里翻 userQuery
            }
            subQueries = List.of(fallback);
        }

        log.info("RetrieverAgent processing {} sub-queries", subQueries.size());

        List<Chunk> allChunks = new ArrayList<>();

        for (String query : subQueries) {
            List<Chunk> chunks = retrieveWithReAct(query, MAX_REACT_LOOPS);
            allChunks.addAll(chunks);
        }

        List<Chunk> deduplicated = deduplicate(allChunks);
        ctx.setVariable("documents", deduplicated);

        String retrievedContext = buildContextText(deduplicated);
        ctx.setVariable("retrievedContext", retrievedContext);

        log.info("RetrieverAgent completed: {} unique chunks retrieved", deduplicated.size());
        return "retrieved " + deduplicated.size() + " chunks";
    }

    /**
     * 递归执行 ReAct 循环，直到检索到足够多的文档或超过最大循环次数。
     * <p>
     * 每次循环会根据 LLM 的判断，自动改写 Query 并重新检索。
     * 如果改写后的 Query 无法满足需求，会继续改写，最多循环 3 次。
     * <p>
     * 如果改写后的 Query 包含多个子任务，会递归执行本方法，每个子任务都执行一次 ReAct 循环。
     * <p>
     * 最终返回所有子任务的文档合并结果。
     */
    private List<Chunk> retrieveWithReAct(String query, int remainingLoops) {
        if (remainingLoops <= 0) {
            return hybridRetriever.retrieve(query);//如果剩余循环次数为 0，直接返回检索结果
               }

        List<Chunk> chunks = hybridRetriever.retrieve(query);//执行检索

        if (chunks.size() < 3 && remainingLoops > 1) {
            log.info("RetrieverAgent ReAct loop: only {} chunks found, rewriting query", chunks.size());
            QueryRewriterImpl.RewriteResult rewritten = queryRewriter.rewrite(query);//改写查询
            if (rewritten.type() == QueryRewriterImpl.RewriteResult.Type.DECOMPOSE) {
                List<Chunk> allChunks = new ArrayList<>(chunks);//初始化所有文档列表
                for (String subQuery : rewritten.subQueries()) {
                    allChunks.addAll(retrieveWithReAct(subQuery, remainingLoops - 1));//递归执行本方法，每个子任务都执行一次 ReAct 循环
                }
                return allChunks;
            } else {
                String newQuery = rewritten.getEffectiveQueries().getFirst();//获取第一个有效查询语句
                //如果改写后的 Query 包含多个子任务，会递归执行本方法，每个子任务都执行一次 ReAct 循环
                List<Chunk> moreChunks = retrieveWithReAct(newQuery, remainingLoops - 1);//递归执行本方法，每个子任务都执行一次 ReAct 循环
                List<Chunk> combined = new ArrayList<>(chunks);//初始化合并文档列表
                combined.addAll(moreChunks);//合并所有子任务的文档到合并文档列表
                return combined;
            }
        }

        return chunks;
    }

    /**
     * 去重，保留每个文档 ID 的第一个出现。
     * <p>
     * 用于合并所有子任务的文档时，避免重复。
     */
    private List<Chunk> deduplicate(List<Chunk> chunks) {
        List<Chunk> unique = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Chunk chunk : chunks) {
            if (seen.add(chunk.getId())) {
                unique.add(chunk);
            }
        }
        return unique;
    }

    /**
     * 构建上下文文本，用于生成答案。
     * <p>
     * 每个文档内容前添加 [来源 N] 标注，N 为文档在列表中的索引。防止AI编造没有的内容。
     * <p>
     * 最终返回一个包含所有文档内容的字符串。
     */
    private String buildContextText(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            sb.append("[来源 ").append(i + 1).append("] ");
            sb.append(chunk.getContent());
            sb.append("\n---\n");
        }
        return sb.toString();
    }
}

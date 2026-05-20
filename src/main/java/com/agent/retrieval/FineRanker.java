package com.agent.retrieval;

import com.agent.core.Chunk;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
//调用大模型进行打分
public class FineRanker {

    private static final String RERANK_PROMPT = """
            你是一个文档相关性评分助手。请根据用户查询，对以下文档片段进行相关性评分。

            评分标准：
            - 10分：完全匹配，直接回答查询
            - 7-9分：高度相关，包含查询的关键信息
            - 4-6分：部分相关，涉及相关主题但不够直接
            - 1-3分：弱相关，仅略微涉及
            - 0分：完全无关

            用户查询：%s

            文档片段列表：
            %s

            请按以下格式输出，每行一个评分，格式为：<片段编号>: <分数>
            只输出评分结果，不要输出其他内容。
            """;

    private final DeepSeekChatClient chatClient;

    public FineRanker(DeepSeekChatClient chatClient) {
        this.chatClient = chatClient;
    }

    // 对候选文档进行细粒度排序
    public List<RankedChunk> rerank(String query, List<RankedChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 如果候选文档数量小于等于 topK，直接按粗粒度分数排序
        // 因为候选文档数量较少，排序效率较高
        // 否则，调用大模型进行细粒度排序
        // 最后，根据细粒度分数排序
        // 最后，根据细粒度排名设置细粒度排名
        // 最后，返回 topK 个文档
        if (candidates.size() <= topK) {
            candidates.sort(Comparator.comparingDouble(r -> -r.fineScore()));
            return candidates;
        }

        try {
            String prompt = buildRerankPrompt(query, candidates);
            String response = chatClient.chat(prompt);
            parseScores(response, candidates);// 解析大模型返回的细粒度分数
            // 并将其设置到对应的 RankedChunk 中
        } catch (Exception e) {
            log.warn("Fine ranker LLM call failed, using coarse scores as fallback: {}", e.getMessage());
            for (RankedChunk rc : candidates) {
                rc.setFineScore(rc.coarseScore());// 如果调用失败，使用粗粒度分数作为回退
                // 并将其设置到对应的 RankedChunk 中
            }
        }

        // 根据细粒度分数排序
        // 并根据细粒度排名设置细粒度排名
        List<RankedChunk> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(r -> -r.fineScore()));

        // 提取 topK 个文档
        int resultSize = Math.min(topK, sorted.size());
        List<RankedChunk> topResults = sorted.subList(0, resultSize);

        // 设置细粒度排名
        // 从 1 开始，每个文档的排名递增
        for (int i = 0; i < topResults.size(); i++) {
            topResults.get(i).setFineRank(i + 1);
        }

        log.debug("Fine ranker: {} -> {} candidates", candidates.size(), topResults.size());
        return topResults;// 返回 topK 个排序好的文档
    }

    // 构建细粒度排序提示
    private String buildRerankPrompt(String query, List<RankedChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            Chunk chunk = candidates.get(i).chunk();// 获取文档片段
            String snippet = chunk.getContent();// 提取文档内容
            if (snippet.length() > 500) {
                snippet = snippet.substring(0, 500) + "...";
            }
            sb.append("[").append(i + 1).append("] ").append(snippet).append("\n\n");
        }
        // 构建提示词
        return String.format(RERANK_PROMPT, query, sb.toString());
    }

    // 解析大模型返回的细粒度分数
    // 并将其设置到对应的 RankedChunk 中
    private void parseScores(String response, List<RankedChunk> candidates) {
        String[] lines = response.split("\\n");// 按行解析
        // 每行包含一个文档片段的编号和细粒度分数
        // 格式为：<片段编号>: <分数>
        // 只输出评分结果，不要输出其他内容
        for (String line : lines) {
            line = line.trim();
            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;

            try {
                // 提取编号和分数
                String numPart = line.substring(0, colonIdx).replaceAll("[^0-9]", "").trim();
                String scorePart = line.substring(colonIdx + 1).trim().replaceAll("[^0-9.]", "");

                if (numPart.isEmpty() || scorePart.isEmpty()) continue;

                // 转换为整数和浮点数
                // 并将分数归一化到 [0, 1] 范围
                // 因为大模型返回的分数范围为 [0, 10]
                int index = Integer.parseInt(numPart) - 1;
                double score = Double.parseDouble(scorePart);

                if (index >= 0 && index < candidates.size()) {
                    // 将大模型返回的分数归一化到 [0, 1] 范围
                    // 并将分数设置到对应的 RankedChunk 中的 fineScore 字段中
                    candidates.get(index).setFineScore(score / 10.0);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }
}

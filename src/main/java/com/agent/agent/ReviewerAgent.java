package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 质量审核 Agent，以 LLM-as-Judge 方式审阅生成答案的质量。
 * <p>
 * 执行模式：Reflection（审阅 → 批评 → 回退重写）。
 * 审核标准包括事实准确性、引用完整性、回答完整性和无幻觉。
 * 不通过时写入 critique，由 OrchestratorAgent 触发 GeneratorAgent 重写循环。
 * <p>
 * 输入（ctx 读取）：answer, retrievedContext
 * 输出（ctx 写入）：reviewPassed, reviewComment, critique
 */
@Slf4j
@Component("reviewerAgent")
public class ReviewerAgent implements Agent {

    private static final String REVIEW_PROMPT = """
            你是一个严格的质量审核员。请审核以下 AI 回答的质量。

            【参考文档内容】
            %s

            【AI 回答】
            %s

            审核标准：
            1. 事实准确性：答案是否与文档内容一致？是否存在编造的数据？
            2. 引用完整性：是否标注了引用来源 [来源 N]？
            3. 完整性：是否完整回答了用户的所有子问题？
            4. 无幻觉：有没有文档中不存在的信息被编造出来？

            请回复格式：
            通过：PASS
            不通过：FAIL|<具体批评意见>
            """;

    private static final int MAX_RETRY = 3;

    private final DeepSeekChatClient llm;

    public ReviewerAgent(DeepSeekChatClient llm) {
        this.llm = llm;
    }

    @Override
    public String name() {
        return "reviewer";
    }

    /**
     * 执行审核。
     * <p>
     * 从 ctx 中获取 answer 和 retrievedContext，调用 LLM 进行审核。
     * <p>
     * 最终返回审核结果，格式为 "PASS" 或 "FAIL|<具体批评意见>"。但是并没有使用。知识相当于日志一样
     */
    @Override
    public String execute(AgentContext ctx) {
        String answer = ctx.getVariable("answer", "");
        String retrievedContext = ctx.getVariable("retrievedContext", "");

        if (answer.isEmpty()) {
            log.warn("ReviewerAgent: no answer to review");
            ctx.setVariable("reviewPassed", true);
            ctx.setVariable("finalAnswer", answer);
            return "PASS (empty answer)";
        }

        int retryCount = ctx.getVariable("reviewRetryCount", 0);
        if (retryCount >= MAX_RETRY) {
            log.warn("ReviewerAgent: max retry ({}) reached, forcing PASS", MAX_RETRY);
            ctx.setVariable("reviewPassed", true);
            ctx.setVariable("finalAnswer", answer);
            ctx.setVariable("reviewRetryCount", 0);
            return "PASS (forced after max retry)";
        }

        String review = doReview(answer, retrievedContext);//执行审核
        log.info("ReviewerAgent review result: {}", review);

        if (review.startsWith("PASS")) {
            ctx.setVariable("reviewPassed", true);
            ctx.setVariable("finalAnswer", answer);
            ctx.setVariable("reviewRetryCount", 0);
            return review;
        }

        String critique = extractCritique(review);//提取批评意见
        ctx.setVariable("reviewPassed", false);
        ctx.setVariable("critique", critique);//设置批评意见
        ctx.setVariable("reviewRetryCount", retryCount + 1);
        log.info("ReviewerAgent: answer failed review, critique: {}", critique);
        return review;
    }

    /**
     * 执行审核。
     * <p>
     * 从 ctx 中获取 answer 和 retrievedContext，调用 LLM 进行审核。
     * <p>
     * 最终返回审核结果，格式为 "PASS" 或 "FAIL|<具体批评意见>"。
     */
    private String doReview(String answer, String retrievedContext) {
        String prompt = String.format(REVIEW_PROMPT, retrievedContext, answer);
        try {
            return llm.chat(prompt).trim();//调用LLM进行审核
        } catch (Exception e) {
            log.warn("ReviewerAgent LLM call failed: {}", e.getMessage());
            return "PASS";
        }
    }

    /**
     * 从审核结果中提取具体意见。
     * <p>
     * 从审核结果中提取具体批评意见，格式为 "FAIL|<具体批评意见>"。
     * <p>
     * 如果审核结果不是 "FAIL|<具体批评意见>" 格式，返回默认值 "回答质量不达标，请改进。"。
     */
    private String extractCritique(String review) {
        int idx = review.indexOf("|");
        if (idx >= 0 && idx < review.length() - 1) {
            return review.substring(idx + 1).trim();
        }
        return "回答质量不达标，请改进。";
    }
}

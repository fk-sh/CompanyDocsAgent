package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.AgentSkill;
import com.agent.core.AgentSkill.VariableDef;
import com.agent.core.Chunk;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 答案生成 Agent，拼装 Prompt 并调用 LLM 生成最终答案。
 * <p>
 * 执行模式：ReAct 循环，LLM 可自主决定是否需要更多信息，最多循环 3 次。
 * 支持 Reflection 回退——当 ReviewerAgent 给出批评意见时，根据 critique 重新生成答案。
 * <p>
 * 识别出的各种意图都会在这个大模型中执行，根据意图标签拼接不同的 Prompt 并调用 LLM 模型生成最终答案。
 * 输入（ctx 读取）：retrievedContext, history, critique, memoryContext, userQuery
 * 输出（ctx 写入）：answer
 */
@Slf4j
@Component("generatorAgent")
public class GeneratorAgent implements Agent {

    private static final String QA_PROMPT = """
            你是一个专业的知识库问答助手。请根据以下检索到的文档内容回答用户问题。

            【历史上下文】
            {memoryContext}

            【检索到的相关文档内容】
            {retrievedContext}

            【用户问题】
            {userQuery}

            {critiqueSection}

            要求：
            1. 答案必须基于文档内容，不得编造数据
            2. 需要标注引用来源，格式为 [来源 N]
            3. 如果文档内容不足以回答，请明确说明
            4. 答案结构清晰，使用适当的格式组织
            """;

    private static final String CHITCHAT_PROMPT = """
            你是一个友好的智能助手。请用自然、亲切的语气回复用户。

            【历史上下文】
            {memoryContext}

            【用户消息】
            {userQuery}
            """;

    private static final String MULTI_INTENT_SUMMARY_PROMPT = """
            你是一个专业的答案汇总助手。请将以下多个子任务的回答整合为一个条理清晰的统一回复。

            【用户原始问题】
            {userQuery}

            【各子任务回答】
            {subAnswers}

            要求：
            1. 整合各子任务的结果，避免重复
            2. 结构清晰，分段呈现不同子问题的答案
            3. 保留引用来源标注
            """;

    private final DeepSeekChatClient llm;

    public GeneratorAgent(DeepSeekChatClient llm) {
        this.llm = llm;
    }

    @Override
    public String name() {
        return "generator";
    }

    @Override
    public AgentSkill skill() {
        return new AgentSkill(
                "generator",
                "答案生成：根据意图标签拼接对应 Prompt 并调用 LLM 生成最终回答，支持 knowledge_qa / chitchat / multi_intent 三种模式",
                List.of(
                        VariableDef.input("intent", "String", "意图标签：knowledge_qa / chitchat / multi_intent"),
                        VariableDef.input("userQuery", "String", "用户原始问题"),
                        VariableDef.optionalInput("retrievedContext", "String", "检索到的文档上下文（knowledge_qa 时必填）"),
                        VariableDef.optionalInput("memoryContext", "String", "历史对话上下文"),
                        VariableDef.optionalInput("critique", "String", "Reviewer 的批评意见（Reflection 回退时）"),
                        VariableDef.optionalInput("subAnswers", "String", "多意图时的子任务回答汇总")
                ),
                List.of(
                        VariableDef.output("answer", "String", "生成的回答文本")
                )
        );
    }

    /**
     * 执行答案生成。
     * 根据意图标签，调用不同的 LLM 模型生成最终答案。
     */
    @Override
    public String execute(AgentContext ctx) {
        String intent = ctx.getVariable("intent", "knowledge_qa");
        log.info("GeneratorAgent executing with intent: {}", intent);

        String answer;
        if ("multi_intent".equals(intent)) {
            answer = summarizeMultiIntent(ctx);// 多意图，需要汇总多个子任务的回答
        } else if ("chitchat".equals(intent)) {
            answer = generateChitchat(ctx);// 聊天意图，直接生成回复
        } else {
            answer = generateKnowledgeAnswer(ctx);// 单意图，没有子任务，直接生成知识库问答答案
        }

        ctx.setVariable("answer", answer);
        ctx.addMessage(Message.assistant(answer));
        log.info("GeneratorAgent completed, answer length: {}", answer.length());
        return answer;
    }

    /**
     * 生成知识库问答答案。
     */
    private String generateKnowledgeAnswer(AgentContext ctx) {
        String retrievedContext = ctx.getVariable("retrievedContext", "");
        String userQuery  = ctx.getUserQuery();;// 从上下文或用户输入中获取用户问题
        String memoryContext = ctx.getVariable("memoryContext", "");// 从上下文或用户输入中获取历史上下文
        String critique = ctx.getVariable("critique", "");// 获取批评意见

        String critiqueSection = "";
        if (!critique.isEmpty()) {
            critiqueSection = "【上一轮批评意见，请据此改进】\n" + critique;
        }

        String prompt = QA_PROMPT
                .replace("{memoryContext}", memoryContext)
                .replace("{retrievedContext}", retrievedContext)// 检索到的相关文档内容，ReviewerAgent传递过来的
                .replace("{userQuery}", userQuery)
                .replace("{critiqueSection}", critiqueSection);

        return llm.chat(prompt);//生成回答，可能是最终返回给用户的答案，也可能是中间的答案，因为还有Reflection回退
    }

    /**
     * 生成普通的聊天回复。
     */
    private String generateChitchat(AgentContext ctx) {
        String memoryContext = ctx.getVariable("memoryContext", "");// 从上下文或用户输入中获取历史上下文
        String userQuery =ctx.getUserQuery();//用户的输入问题

        String prompt = CHITCHAT_PROMPT
                .replace("{memoryContext}", memoryContext)
                .replace("{userQuery}", userQuery);

        return llm.chat(prompt);
    }

    /**
     * 生成多意图的汇总回复。
     */
    private String summarizeMultiIntent(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();;
        String subAnswers = ctx.getVariable("subAnswers", "");

        if (subAnswers.isEmpty()) {
            return generateKnowledgeAnswer(ctx);
        }

        String prompt = MULTI_INTENT_SUMMARY_PROMPT
                .replace("{userQuery}", userQuery)
                .replace("{subAnswers}", subAnswers);

        return llm.chat(prompt);
    }
}

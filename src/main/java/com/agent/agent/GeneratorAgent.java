package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.AgentSkill;
import com.agent.core.AgentSkill.VariableDef;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import com.agent.llm.DeepSeekStreamingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;

@Slf4j
@Component("generatorAgent")
public class GeneratorAgent implements Agent {

    private static final String QA_PROMPT = """
            你是一个专业的知识库问答助手。你的回答必须严格基于以下【检索到的相关文档内容】。

            【历史上下文】
            {memoryContext}

            【检索到的相关文档内容】
            {retrievedContext}

            【用户问题】
            {userQuery}

            {critiqueSection}

            核心规则（必须遵守）：
            1. 严格基于文档内容回答，绝对禁止使用你自己的知识编造数据或定义
            2. 如果文档内容为空或完全不包含与用户问题相关的信息，请直接回复："抱歉，当前知识库中未检索到与您问题相关的文档内容，请提供相关文档或换一种方式提问。"
            3. 需要标注引用来源，格式为 [来源 N]
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
    private final DeepSeekStreamingClient streamingClient;

    public GeneratorAgent(DeepSeekChatClient llm, DeepSeekStreamingClient streamingClient) {
        this.llm = llm;
        this.streamingClient = streamingClient;
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

    @Override
    public String execute(AgentContext ctx) {
        String intent = ctx.getVariable("intent", "knowledge_qa");
        log.info("GeneratorAgent executing with intent: {}", intent);

        String answer;
        if ("multi_intent".equals(intent)) {
            answer = summarizeMultiIntent(ctx);
        } else if ("chitchat".equals(intent)) {
            answer = generateChitchat(ctx);
        } else {
            answer = generateKnowledgeAnswer(ctx);
        }

        ctx.setVariable("answer", answer);
        ctx.addMessage(Message.assistant(answer));
        log.info("GeneratorAgent completed, answer length: {}", answer.length());
        return answer;
    }

    public Flux<String> executeStream(AgentContext ctx) {
        String intent = ctx.getVariable("intent", "knowledge_qa");

        String prompt;
        if ("chitchat".equals(intent)) {
            prompt = buildChitchatPrompt(ctx);
        } else if ("multi_intent".equals(intent)) {
            prompt = buildMultiIntentPrompt(ctx);
        } else {
            prompt = buildKnowledgePrompt(ctx);
        }

        Sinks.Many<String> sink = Sinks.many().replay().latest();
        StringBuilder fullAnswer = new StringBuilder();

        streamingClient.streamRaw(prompt)
                .doOnNext(token -> {
                    fullAnswer.append(token);
                    sink.tryEmitNext(token);
                })
                .doOnComplete(() -> {
                    String answer = fullAnswer.toString();
                    ctx.setVariable("answer", answer);
                    ctx.addMessage(Message.assistant(answer));
                    log.info("GeneratorAgent stream complete, answer length: {}", answer.length());
                    sink.tryEmitComplete();
                })
                .doOnError(e -> {
                    log.error("GeneratorAgent stream error: {}", e.getMessage());
                    sink.tryEmitError(e);
                })
                .subscribe();

        return sink.asFlux();
    }

    private String buildKnowledgePrompt(AgentContext ctx) {
        String retrievedContext = ctx.getVariable("retrievedContext", "");
        String userQuery = ctx.getUserQuery();
        String memoryContext = ctx.getVariable("memoryContext", "");
        String critique = ctx.getVariable("critique", "");

        String critiqueSection = "";
        if (!critique.isEmpty()) {
            critiqueSection = "【上一轮批评意见，请据此改进】\n" + critique;
        }

        return QA_PROMPT
                .replace("{memoryContext}", memoryContext)
                .replace("{retrievedContext}", retrievedContext)
                .replace("{userQuery}", userQuery)
                .replace("{critiqueSection}", critiqueSection);
    }

    private String buildChitchatPrompt(AgentContext ctx) {
        String memoryContext = ctx.getVariable("memoryContext", "");
        String userQuery = ctx.getUserQuery();
        return CHITCHAT_PROMPT
                .replace("{memoryContext}", memoryContext)
                .replace("{userQuery}", userQuery);
    }

    private String buildMultiIntentPrompt(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();
        String subAnswers = ctx.getVariable("subAnswers", "");
        if (subAnswers.isEmpty()) {
            return buildKnowledgePrompt(ctx);
        }
        return MULTI_INTENT_SUMMARY_PROMPT
                .replace("{userQuery}", userQuery)
                .replace("{subAnswers}", subAnswers);
    }

    private String generateKnowledgeAnswer(AgentContext ctx) {
        return llm.chat(buildKnowledgePrompt(ctx));
    }

    private String generateChitchat(AgentContext ctx) {
        return llm.chat(buildChitchatPrompt(ctx));
    }

    private String summarizeMultiIntent(AgentContext ctx) {
        String subAnswers = ctx.getVariable("subAnswers", "");
        if (subAnswers.isEmpty()) {
            return generateKnowledgeAnswer(ctx);
        }
        return llm.chat(buildMultiIntentPrompt(ctx));
    }
}

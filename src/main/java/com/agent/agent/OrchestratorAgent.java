package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.AgentSkill;
import com.agent.core.AgentSkill.VariableDef;
import com.agent.core.Orchestrator;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编排器 Agent，多 Agent 系统的调度中枢。
 * <p>
 * 继承 {@link Orchestrator} 接口（间接继承 {@link Agent}），
 * 外部通过统一的 {@code agent.execute(context)} 调用。
 * <p>
 * 核心职责：读取 RouterAgent 写入的 intent，按意图分支调度子 Agent。
 * 子 Agent 通过 Spring {@code @Autowired List<Agent>} 自动发现，
 * 按 name() 查找匹配的 Agent 实例。
 * <p>
 * 五条路由链路：
 * <pre>
 *   intent="knowledge_qa"  → RetrieverAgent ⇄ GeneratorAgent (ReAct)
 *   intent="chitchat"      → GeneratorAgent（直接回复，无 ReAct）
 *   intent="weather"       → WeatherAgent (MCP)
 *   intent="doc_ingestion" → IngestionAgent
 *   intent="multi_intent"  → SUB_TASK_LOOP → 各子链路 → GeneratorAgent(汇总)
 * </pre>
 * <p>
 * 最终审查：OrchestratorAgent 对所有链路产出的 finalAnswer 进行 Reflection 评价，
 * 不通过则触发对应链路重试，通过后才正常输出。
 * <p>
 * 输入（ctx 读取）：intent, subTasks（多意图时）
 * 输出（ctx 写入）：finalAnswer
 */
@Slf4j
@Component("orchestratorAgent")
public class OrchestratorAgent implements Orchestrator {

    private static final String EVAL_PROMPT = """
            你是一个严格的质量审核员。请审核以下回答是否达标。

            【用户问题】
            %s

            【回答】
            %s

            审核标准：
            1. 回答是否直接回应了用户问题？是否存在答非所问？
            2. 回答是否清晰、完整、无歧义？
            3. 回答是否不存在明显的事实错误或编造？

            请只回复一个单词：
            PASS — 回答达标，可以输出
            FAIL — 回答不达标，需要重新生成
            """;

    private static final int MAX_REACT_LOOPS = 3;

    private final DeepSeekChatClient llm;

    // 子 Agent 映射表，按 name() 查找
    private final Map<String, Agent> agentMap = new ConcurrentHashMap<>();

    public OrchestratorAgent(List<Agent> agents, DeepSeekChatClient llm) {
        this.llm = llm;
        for (Agent agent : agents) {
            if (agent != this) {
                registerAgent(agent);
            }
        }
        log.info("OrchestratorAgent initialized with {} agents: {}",
                agentMap.size(), agentMap.keySet());
    }

    @Override
    public String name() {
        return "orchestrator";
    }

    @Override
    public AgentSkill skill() {
        return new AgentSkill(
                "orchestrator",
                "编排调度：根据意图标签路由到对应子 Agent 链路（knowledge_qa / chitchat / doc_ingestion / multi_intent），对 knowledge_qa 结果进行最终审查",
                List.of(
                        VariableDef.input("intent", "String", "意图标签"),
                        VariableDef.optionalInput("subTasks", "List<SubTask>", "多意图时的子任务列表")
                ),
                List.of(
                        VariableDef.output("finalAnswer", "String", "编排完成后写入的最终答案")
                )
        );
    }

    @Override
    public String execute(AgentContext ctx) {
        String existingIntent = ctx.getVariable("intent", null);
        if (existingIntent == null) {
            Agent router = findAgent("router");
            if (router != null) {
                router.execute(ctx);
            }
        }
        String intent = ctx.getVariable("intent", "knowledge_qa");
        log.info("OrchestratorAgent executing with intent: {}", intent);

        switch (intent) {
            case "knowledge_qa" -> executeKnowledgeQa(ctx);
            case "chitchat" -> executeChitchat(ctx);
            case "weather" -> executeWeather(ctx);
            case "doc_ingestion" -> executeDocIngestion(ctx);
            case "multi_intent" -> executeMultiIntent(ctx);
            default -> {
                log.warn("Unknown intent '{}', falling back to knowledge_qa", intent);
                executeKnowledgeQa(ctx);
            }
        }

        String finalAnswer = ctx.getVariable("finalAnswer", "");
        if (finalAnswer.isEmpty()) {
            String answer = ctx.getVariable("answer", "抱歉，无法处理您的问题。");
            ctx.setVariable("finalAnswer", answer);
        }

        evaluateAndRefine(ctx, intent);

        log.info("OrchestratorAgent completed, finalAnswer length: {}",
                ctx.<String>getVariable("finalAnswer").length());
        return "ok";
    }

    private void evaluateAndRefine(AgentContext ctx, String intent) {
        if (!"knowledge_qa".equals(intent) && !"multi_intent".equals(intent)) {
            return;
        }

        String finalAnswer = ctx.getVariable("finalAnswer", "");
        if (finalAnswer.isEmpty() || finalAnswer.contains("抱歉，无法处理您的问题")) {
            return;
        }

        String userQuery = ctx.getUserQuery();

        for (int i = 0; i < MAX_REACT_LOOPS; i++) {
            boolean passed = evaluateAnswer(userQuery, finalAnswer);
            if (passed) {
                log.info("Final evaluation passed (attempt {})", i + 1);
                return;
            }

            if (i < MAX_REACT_LOOPS - 1) {
                log.info("Final evaluation failed (attempt {}), regenerating", i + 1);
                String regenerated = regenerateAnswer(userQuery, finalAnswer);
                if (!regenerated.isEmpty()) {
                    ctx.setVariable("finalAnswer", regenerated);
                    finalAnswer = regenerated;
                } else {
                    break;
                }
            } else {
                log.warn("Final evaluation failed after {} attempts, outputting as-is", MAX_REACT_LOOPS);
            }
        }
    }

    private boolean evaluateAnswer(String userQuery, String answer) {
        String prompt = String.format(EVAL_PROMPT, userQuery, answer);
        try {
            String result = llm.chat(prompt).trim().toUpperCase();
            return result.contains("PASS") && !result.contains("FAIL");
        } catch (Exception e) {
            log.warn("Final evaluation LLM call failed: {}", e.getMessage());
            return true;
        }
    }

    private String regenerateAnswer(String userQuery, String previousAnswer) {
        String prompt = """
                以下回答未能通过质量审核。请根据用户问题重新生成一个更好的回答。

                【用户问题】
                %s

                【被驳回的回答】
                %s

                请输出改进后的回答：
                """.formatted(userQuery, previousAnswer);

        try {
            return llm.chat(prompt).trim();
        } catch (Exception e) {
            log.warn("Answer regeneration failed: {}", e.getMessage());
            return "";
        }
    }

    private void executeKnowledgeQa(AgentContext ctx) {
        log.info("Executing knowledge_qa pipeline: Retriever ⇄ Generator (ReAct)");

        Agent retriever = findAgent("retriever");
        Agent generator = findAgent("generator");

        if (retriever == null || generator == null) {
            log.error("Missing required agents for knowledge_qa pipeline");
            ctx.setVariable("finalAnswer", "系统配置错误，缺少必要的 Agent。");
            return;
        }

        String userQuery = ctx.getUserQuery();

        for (int loop = 0; loop < MAX_REACT_LOOPS; loop++) {
            log.info("ReAct loop {}/{}: {}", loop + 1, MAX_REACT_LOOPS,
                    loop == 0 ? "initial retrieval" : "re-retrieval with rewritten query");

            retriever.execute(ctx);
            generator.execute(ctx);

            String answer = ctx.getVariable("answer", "");

            if (isAnswerSufficient(answer)) {
                log.info("ReAct loop {}/{}: answer sufficient, stopping", loop + 1, MAX_REACT_LOOPS);
                break;
            }

            if (loop < MAX_REACT_LOOPS - 1) {
                log.info("ReAct loop {}/{}: answer insufficient, rewriting query for better retrieval", loop + 1, MAX_REACT_LOOPS);
                String rewritten = rewriteQueryForReAct(userQuery, answer);
                ctx.setVariable("subQueries", List.of(rewritten));
                ctx.removeVariable("answer");
                ctx.removeVariable("documents");
                ctx.removeVariable("retrievedContext");
            }
        }
    }

    private boolean isAnswerSufficient(String answer) {
        if (answer == null || answer.isEmpty()) {
            return false;
        }
        String lower = answer.toLowerCase();
        return !lower.contains("未检索到")
                && !lower.contains("知识库中未检索到")
                && !lower.contains("没有找到相关")
                && !lower.contains("无法找到");
    }

    private String rewriteQueryForReAct(String originalQuery, String failedAnswer) {
        String prompt = """
                以下是用户问题和系统未能充分回答的回答。请将用户问题改写为更精准的检索查询，
                以便从知识库中检索到更相关的文档。

                【用户问题】
                %s

                【系统回答（不充分）】
                %s

                请只输出改写后的检索查询（一行，不要任何解释）：
                """.formatted(originalQuery, failedAnswer);

        try {
            String rewritten = llm.chat(prompt).trim();
            log.info("ReAct query rewritten: '{}' → '{}'", originalQuery, rewritten);
            return rewritten.isEmpty() ? originalQuery : rewritten;
        } catch (Exception e) {
            log.warn("ReAct query rewrite failed: {}", e.getMessage());
            return originalQuery;
        }
    }

    /**
     * 执行普通聊天流程
     */
    private void executeChitchat(AgentContext ctx) {
        log.info("Executing chitchat pipeline: Generator only");
        Agent generator = findAgent("generator");
        if (generator != null) {
            generator.execute(ctx);
            ctx.setVariable("finalAnswer", ctx.getVariable("answer"));
        }
    }

    /**
     * 执行天气查询流程
     */
    private void executeWeather(AgentContext ctx) {
        log.info("Executing weather pipeline: WeatherAgent (MCP)");
        Agent weather = findAgent("weather");
        if (weather != null) {
            weather.execute(ctx);
            ctx.setVariable("finalAnswer", ctx.getVariable("answer"));
        }
    }

    /**
     * 执行文档摄入流程
     */
    private void executeDocIngestion(AgentContext ctx) {
        log.info("Executing doc_ingestion pipeline: IngestionAgent only");
        Agent ingestion = findAgent("ingestion");
        if (ingestion != null) {
            String taskId = ingestion.execute(ctx);//根据ctx中的filePath提交文档摄入任务
            String status = ctx.getVariable("ingestionStatus", "UNKNOWN");
            ctx.setVariable("finalAnswer", String.format(
                    "文档摄入任务已提交，任务ID：%s，当前状态：%s", taskId, status));
        }
    }

    private void executeMultiIntent(AgentContext ctx) {
        log.info("Executing multi_intent pipeline: SUB_TASK_LOOP");
        List<SubTask> subTasks = ctx.getVariable("subTasks");
        if (subTasks == null || subTasks.isEmpty()) {
            log.warn("No subTasks found, falling back to knowledge_qa");
            executeKnowledgeQa(ctx);
            return;
        }

        StringBuilder subAnswers = new StringBuilder();
        for (int i = 0; i < subTasks.size(); i++) {
            SubTask task = subTasks.get(i);
            log.info("Processing subtask {}/{}: intent={}, query={}",
                    i + 1, subTasks.size(), task.intent(), task.query());

            ctx.setVariable("intent", task.intent());//用户意图
            ctx.setVariable("subQueries", List.of(task.query()));//查询语句

            switch (task.intent()) {
                case "knowledge_qa" -> executeKnowledgeQa(ctx);
                case "chitchat" -> executeChitchat(ctx);
                case "weather" -> executeWeather(ctx);
                case "doc_ingestion" -> executeDocIngestion(ctx);
                default -> executeKnowledgeQa(ctx);
            }

            String answer = ctx.getVariable("finalAnswer", "");
            if (!answer.isEmpty()) {
                subAnswers.append("[子任务 ").append(i + 1).append(": ")
                        .append(task.query()).append("]\n")//子任务查询语句
                        .append(answer).append("\n\n");//子任务回答
            }
            //以上都在循环之内，因此会执行每一个任务的流程
        }

        ctx.setVariable("subAnswers", subAnswers.toString());// 所有子任务结果拼成一个大字符串
        ctx.setVariable("intent", "multi_intent");//用户意图

        Agent generator = findAgent("generator");
        if (generator != null) {
            generator.execute(ctx);//根据ctx中的query生成回答
        }
        ctx.setVariable("finalAnswer", ctx.getVariable("answer"));
    }

    //根据 name 查找 Agent
    private Agent findAgent(String name) {
        Agent agent = agentMap.get(name);
        if (agent == null) {
            log.warn("Agent '{}' not found in registry, available: {}", name, agentMap.keySet());
        }
        return agent;
    }

    @Override
    public void registerAgent(Agent agent) {
        agentMap.put(agent.name(), agent);
        log.debug("Registered agent: {}", agent.name());
    }

    @Override
    public void removeAgent(String name) {
        agentMap.remove(name);
        log.debug("Removed agent: {}", name);
    }

    @Override
    public List<Agent> getAgents() {
        return new ArrayList<>(agentMap.values());
    }

    public Optional<Agent> getAgent(String name) {
        return Optional.ofNullable(agentMap.get(name));
    }
}

package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Orchestrator;
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
 * 四条路由链路：
 * <pre>
 *   intent="knowledge_qa"  → RetrieverAgent → GeneratorAgent ⇄ ReviewerAgent (Reflection)
 *   intent="chitchat"      → GeneratorAgent
 *   intent="doc_ingestion" → IngestionAgent
 *   intent="multi_intent"  → SUB_TASK_LOOP → 各子链路 → GeneratorAgent(汇总)
 * </pre>
 * <p>
 * 输入（ctx 读取）：intent, subTasks（多意图时）
 * 输出（ctx 写入）：finalAnswer
 */
@Slf4j
@Component("orchestratorAgent")
public class OrchestratorAgent implements Orchestrator {

    // 子 Agent 映射表，按 name() 查找
    private final Map<String, Agent> agentMap = new ConcurrentHashMap<>();
    public OrchestratorAgent(List<Agent> agents) {
        //将所有Agent添加到map集合里边
        for (Agent agent : agents) {
            if (agent != this) {
            registerAgent(agent);// 注册子 Agent 到映射表
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
    public String execute(AgentContext ctx) {
        String intent = ctx.getVariable("intent", "knowledge_qa");
        log.info("OrchestratorAgent executing with intent: {}", intent);

        switch (intent) {
            case "knowledge_qa" -> executeKnowledgeQa(ctx);
            case "chitchat" -> executeChitchat(ctx);
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

        log.info("OrchestratorAgent completed, finalAnswer length: {}",
                ctx.<String>getVariable("finalAnswer").length());
        return "ok";
    }

    private void executeKnowledgeQa(AgentContext ctx) {
        log.info("Executing knowledge_qa pipeline: Retriever → Generator ⇄ Reviewer");

        Agent retriever = findAgent("retriever");
        Agent generator = findAgent("generator");
        Agent reviewer = findAgent("reviewer");

        if (retriever == null || generator == null || reviewer == null) {
            log.error("Missing required agents for knowledge_qa pipeline");
            ctx.setVariable("finalAnswer", "系统配置错误，缺少必要的 Agent。");
            return;
        }

        //执行知识回答流程
        retriever.execute(ctx);//根据ctx中的query查询文档
        generator.execute(ctx);//根据检索到的文档生成回答
        reviewer.execute(ctx);//根据回答生成进行反思

        int maxReflectionLoops = 3;// 最大反射循环次数
        int loop = 0;//当前循环次数
        // 反思循环：如果回答未通过反思，重新生成回答并反思，最多循环 maxReflectionLoops 次
        while (!ctx.<Boolean>getVariable("reviewPassed", true) && loop < maxReflectionLoops) {
            log.info("Reflection loop {}/{}: regenerating answer with critique", loop + 1, maxReflectionLoops);
            generator.execute(ctx);//根据反思结果重新生成回答
            reviewer.execute(ctx);//根据新回答生成进行反思
            loop++;
        }
    }

    /**
     * 执行普通聊天流程
     */
    private void executeChitchat(AgentContext ctx) {
        log.info("Executing chitchat pipeline: Generator only");
        Agent generator = findAgent("generator");
        if (generator != null) {
            generator.execute(ctx);//根据ctx中的query生成回答
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

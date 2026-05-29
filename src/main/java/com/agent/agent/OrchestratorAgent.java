package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import com.agent.llm.DeepSeekStreamingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component("orchestratorAgent")
public class OrchestratorAgent implements Agent {

    private static final String PLAN_PROMPT = """
            你是一个意图分类和任务规划助手。分析用户输入，制定执行计划。

            === 可用工具 ===
            - knowledge_search: 从知识库检索文档内容（如公司制度、技术文档、编程概念等）
            - weather_query: 查询指定城市的实时天气信息

            === 分类规则 ===
            1. knowledge_qa: 需要从知识库检索的问题（技术概念、公司制度、文档查询、任何需要知识支撑的问题）
            2. chitchat: 闲聊、问候、纯寒暄（如：你好、谢谢、讲个笑话），不需要检索
            3. weather: 查询天气信息（如：今天天气、某地天气、气温）
            4. multi_intent: 包含多个独立不相关的子任务

            === 注意 ===
            - 简短术语/名词（如"栈溢出"、"Docker"、"微服务"）也归类为 knowledge_qa
            - 闲聊只需问候寒暄，归类 chitchat
            - 天气相关问题要注意提取城市名
            - 多个不相关的问题一起问时归类 multi_intent，分别处理

            输出 JSON（严格格式，不要任何其他文字）：
            单意图：
            {"intent":"knowledge_qa","retrievalQueries":["改写后的query1","改写后的query2"],"needsRetrieval":true}
            {"intent":"chitchat","needsRetrieval":false}
            {"intent":"weather","weatherCity":"杭州","needsRetrieval":false}
            多意图：
            {"intent":"multi_intent","subPlans":[{"intent":"weather","query":"今天杭州天气","weatherCity":"杭州","needsRetrieval":false},{"intent":"knowledge_qa","query":"线程池怎么配","retrievalQueries":["Java线程池核心参数配置","ThreadPoolExecutor最佳实践"],"needsRetrieval":true}]}
            """;

    private static final String[] CHITCHAT_KEYWORDS = {
            "你好", "您好", "嗨", "hello", "hi", "hey",
            "早上好", "晚上好", "下午好", "早安", "晚安",
            "再见", "拜拜", "bye", "谢谢", "感谢", "thanks",
            "哈哈", "呵呵", "嘻嘻", "你是谁", "你能做什么",
            "讲个笑话", "讲个故事"
    };

    private final DeepSeekChatClient llm;
    private final DeepSeekStreamingClient streamingClient;
    private final RetrieverAgent retrieverAgent;
    private final GeneratorAgent generatorAgent;
    private final WeatherAgent weatherAgent;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public OrchestratorAgent(DeepSeekChatClient llm,
                             DeepSeekStreamingClient streamingClient,
                             @Qualifier("retrieverAgent") Agent retrieverAgent,
                             @Qualifier("generatorAgent") Agent generatorAgent,
                             @Qualifier("weatherAgent") Agent weatherAgent) {
        this.llm = llm;
        this.streamingClient = streamingClient;
        this.retrieverAgent = (RetrieverAgent) retrieverAgent;
        this.generatorAgent = (GeneratorAgent) generatorAgent;
        this.weatherAgent = (WeatherAgent) weatherAgent;
        log.info("OrchestratorAgent initialized with 3 agents: retriever, generator, weather");
    }

    @Override
    public String name() {
        return "orchestrator";
    }

    @Override
    public String execute(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();
        log.info("OrchestratorAgent: query='{}'", userQuery);

        Plan plan = makePlan(userQuery);
        ctx.setVariable("intent", plan.intent());

        if (plan.isMultiIntent()) {
            return executeMultiIntent(ctx, plan);
        }
        return executeSingleIntent(ctx, plan);
    }

    public Flux<String> executeStream(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();
        log.info("OrchestratorAgent stream: query='{}'", userQuery);

        Plan plan = makePlan(userQuery);
        ctx.setVariable("intent", plan.intent());

        if (plan.isChitchat()) {
            return generatorAgent.generateStream(ctx);
        }

        if ("weather".equals(plan.intent())) {
            String answer = weatherAgent.query(plan.weatherCity());
            ctx.setVariable("finalAnswer", answer);
            return Flux.just(answer);
        }

        String retrievedContext = retrieverAgent.retrieve(plan.retrievalQueries());
        ctx.setVariable("retrievedContext", retrievedContext);
        return generatorAgent.generateStream(ctx);
    }

    private Plan makePlan(String userQuery) {
        if (isLikelyChitchat(userQuery)) {
            return Plan.chitchat();
        }

        try {
            String response = llm.chat(PLAN_PROMPT, userQuery).trim();
            log.info("OrchestratorAgent Plan LLM response: {}", response);

            String json = extractJson(response);
            if (json == null) {
                log.warn("OrchestratorAgent: could not extract JSON, falling back to knowledge_qa");
                return Plan.knowledgeQa(List.of(userQuery));
            }

            return parsePlan(json, userQuery);
        } catch (Exception e) {
            log.warn("OrchestratorAgent: plan LLM failed: {}, fallback to knowledge_qa", e.getMessage());
            return Plan.knowledgeQa(List.of(userQuery));
        }
    }

    private String executeSingleIntent(AgentContext ctx, Plan plan) {
        String userQuery = ctx.getUserQuery();

        switch (plan.intent()) {
            case "weather" -> {
                String answer = weatherAgent.query(plan.weatherCity());
                ctx.setVariable("finalAnswer", answer);
                ctx.addMessage(Message.assistant(answer));
                return answer;
            }
            case "chitchat" -> {
                String answer = generatorAgent.generate(ctx);
                ctx.setVariable("finalAnswer", answer);
                ctx.addMessage(Message.assistant(answer));
                return answer;
            }
            case "knowledge_qa" -> {
                log.info("OrchestratorAgent: executing knowledge_qa pipeline");
                String retrievedContext = retrieverAgent.retrieve(plan.retrievalQueries());
                ctx.setVariable("retrievedContext", retrievedContext);
                String answer = generatorAgent.generate(ctx);
                ctx.setVariable("finalAnswer", answer);
                ctx.addMessage(Message.assistant(answer));
                return answer;
            }
            default -> {
                log.warn("OrchestratorAgent: unknown intent '{}', fallback to knowledge_qa", plan.intent());
                String retrievedContext = retrieverAgent.retrieve(List.of(userQuery));
                ctx.setVariable("retrievedContext", retrievedContext);
                String answer = generatorAgent.generate(ctx);
                ctx.setVariable("finalAnswer", answer);
                ctx.addMessage(Message.assistant(answer));
                return answer;
            }
        }
    }

    private String executeMultiIntent(AgentContext ctx, Plan plan) {
        List<Plan.SubPlan> subPlans = plan.subPlans();
        log.info("OrchestratorAgent: executing {} sub-plans in parallel", subPlans.size());

        List<CompletableFuture<SubAnswer>> futures = new ArrayList<>();
        for (Plan.SubPlan sp : subPlans) {
            futures.add(CompletableFuture.supplyAsync(() -> executeSubPlan(sp), executor)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .exceptionally(ex -> new SubAnswer(sp.query(), "子任务执行异常: " + ex.getMessage())));
        }

        List<SubAnswer> subAnswers = futures.stream()
                .map(f -> {
                    try {
                        return f.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        return new SubAnswer("unknown", "子任务超时");
                    }
                })
                .toList();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < subAnswers.size(); i++) {
            SubAnswer sa = subAnswers.get(i);
            sb.append("[子任务 ").append(i + 1).append(": ").append(sa.query()).append("]\n");
            sb.append(sa.answer()).append("\n\n");
        }
        ctx.setVariable("subAnswers", sb.toString());
        ctx.setVariable("intent", "multi_intent");

        String finalAnswer = generatorAgent.generate(ctx);
        ctx.setVariable("finalAnswer", finalAnswer);
        ctx.addMessage(Message.assistant(finalAnswer));
        return finalAnswer;
    }

    private SubAnswer executeSubPlan(Plan.SubPlan subPlan) {
        switch (subPlan.intent()) {
            case "weather" -> {
                String answer = weatherAgent.query(subPlan.weatherCity());
                return new SubAnswer(subPlan.query(), answer);
            }
            case "knowledge_qa" -> {
                String retrievedContext = retrieverAgent.retrieve(subPlan.retrievalQueries());

                AgentContext subCtx = new AgentContext("sub", subPlan.query());
                subCtx.setVariable("retrievedContext", retrievedContext);
                subCtx.setVariable("intent", "knowledge_qa");

                String answer = generatorAgent.generate(subCtx);
                return new SubAnswer(subPlan.query(), answer);
            }
            case "chitchat" -> {
                AgentContext subCtx = new AgentContext("sub", subPlan.query());
                subCtx.setVariable("intent", "chitchat");
                String answer = generatorAgent.generate(subCtx);
                return new SubAnswer(subPlan.query(), answer);
            }
            default -> {
                return new SubAnswer(subPlan.query(), "无法识别的子任务类型: " + subPlan.intent());
            }
        }
    }

    private boolean isLikelyChitchat(String query) {
        if (query == null) return false;
        String lower = query.toLowerCase().trim();
        for (String kw : CHITCHAT_KEYWORDS) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String extractJson(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Plan parsePlan(String json, String fallbackQuery) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = mapper.readValue(json, Map.class);

            String intent = (String) map.get("intent");
            if (intent == null) {
                return Plan.knowledgeQa(List.of(fallbackQuery));
            }

            if ("multi_intent".equals(intent)) {
                List<Map<String, Object>> subPlansRaw = (List<Map<String, Object>>) map.get("subPlans");
                if (subPlansRaw == null || subPlansRaw.isEmpty()) {
                    return Plan.knowledgeQa(List.of(fallbackQuery));
                }
                List<Plan.SubPlan> subPlans = subPlansRaw.stream()
                        .map(this::parseSubPlan)
                        .collect(Collectors.toList());
                return Plan.multiIntent(subPlans);
            }

            return switch (intent) {
                case "chitchat" -> Plan.chitchat();
                case "weather" -> {
                    String city = (String) map.get("weatherCity");
                    yield Plan.weather(city != null ? city : "北京");
                }
                case "knowledge_qa" -> {
                    List<String> queries = (List<String>) map.get("retrievalQueries");
                    if (queries == null || queries.isEmpty()) {
                        queries = List.of(fallbackQuery);
                    }
                    yield Plan.knowledgeQa(queries);
                }
                default -> Plan.knowledgeQa(List.of(fallbackQuery));
            };
        } catch (Exception e) {
            log.warn("OrchestratorAgent: plan parse failed: {}", e.getMessage());
            return Plan.knowledgeQa(List.of(fallbackQuery));
        }
    }

    @SuppressWarnings("unchecked")
    private Plan.SubPlan parseSubPlan(Map<String, Object> map) {
        String intent = (String) map.get("intent");
        String query = (String) map.getOrDefault("query", "");
        boolean needsRetrieval = Boolean.TRUE.equals(map.get("needsRetrieval"));
        boolean isChitchat = Boolean.TRUE.equals(map.get("isChitchat"));
        List<String> queries = (List<String>) map.get("retrievalQueries");
        String weatherCity = (String) map.get("weatherCity");

        return new Plan.SubPlan(intent, query, queries, weatherCity, needsRetrieval, isChitchat);
    }

    private record SubAnswer(String query, String answer) {}
}

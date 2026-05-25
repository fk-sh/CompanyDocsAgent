package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.AgentSkill;
import com.agent.core.AgentSkill.VariableDef;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图识别 Agent，对用户输入做意图分类和子任务拆解。
 * <p>
 * 执行模式：Plan（一次性分类），读取 userQuery 和 history，
 * 通过 LLM 将输入归类为 knowledge_qa / chitchat / doc_ingestion / multi_intent，
 * 结果写入 context.variables。
 * <p>
 * 输入（ctx 读取）：userQuery, history
 * 输出（ctx 写入）：intent, subQueries, subTasks（多意图时）
 */
@Slf4j
@Component("routerAgent")
public class RouterAgent implements Agent {

    private static final String INTENT_PROMPT = """
            你是一个意图分类助手。请将用户输入归类为以下类型之一：

            1. knowledge_qa：需要从知识库检索文档来回答问题（如：询问公司制度、财务数据、技术文档、技术术语解释、编程概念等）
            2. chitchat：闲聊、问候、纯寒暄（如：你好、讲个笑话），不需要检索文档
            3. weather：查询天气信息（如：今天天气怎么样、北京天气、上海气温）
            4. doc_ingestion：涉及文档上传、入库、处理等操作
            5. multi_intent：包含多个独立子任务，需要分别处理

            分类规则：
            - 如果用户只是打招呼、闲聊，归类为 chitchat
            - 如果用户询问天气相关的问题，归类为 weather
            - 如果用户询问任何需要知识支撑的问题（包括技术术语、概念解释、专业名词），归类为 knowledge_qa
            - 注意：即使是简短的术语或名词（如"栈溢出"、"微服务"、"Docker"），也需要从知识库检索，归类为 knowledge_qa
            - 如果用户提到上传、导入、处理文档，归类为 doc_ingestion
            - 如果用户一次性提出多个不同类型的问题，归类为 multi_intent，并拆解子任务

            用户输入：%s

            输出格式（单选）：
            单意图：直接回复意图标签（knowledge_qa / chitchat / weather / doc_ingestion）
            多意图：回复 multi_intent 并另起一行列出子任务，每行格式：- intent:query
            """;

    private static final String[] CHITCHAT_KEYWORDS = {
            "你好", "您好", "嗨", "嗨喽", "哈喽", "hello", "hi", "hey",
            "早上好", "晚上好", "下午好", "早安", "晚安",
            "再见", "拜拜", "bye",
            "谢谢", "感谢", "thanks", "thank you",
            "不客气", "没关系",
            "好的", "ok", "okay",
            "嗯", "哦", "啊",
            "哈哈", "呵呵", "嘻嘻",
            "你是谁", "你叫什么", "what is your name",
            "你能做什么", "你能干嘛", "what can you do",
            "讲个笑话", "讲个故事"
    };

    // 子任务正则表达式，匹配 "- intent:query" 格式
    private static final Pattern SUB_TASK_PATTERN = Pattern.compile("^\\s*-\\s*(\\w+)\\s*:\\s*(.+)$", Pattern.MULTILINE);

    private final DeepSeekChatClient llm;

    public RouterAgent(DeepSeekChatClient llm) {
        this.llm = llm;
    }

    @Override
    public String name() {
        return "router";
    }

    @Override
    public AgentSkill skill() {
        return new AgentSkill(
                "router",
                "意图识别：分析用户输入，归类为 knowledge_qa / chitchat / weather / doc_ingestion / multi_intent，多意图时拆解子任务",
                List.of(
                        VariableDef.input("userQuery", "String", "用户原始输入"),
                        VariableDef.optionalInput("history", "List<Message>", "当前会话历史")
                ),
                List.of(
                        VariableDef.output("intent", "String", "意图标签"),
                        VariableDef.output("subQueries", "List<String>", "子任务查询列表"),
                        VariableDef.output("subTasks", "List<SubTask>", "多意图时的子任务列表")
                )
        );
    }

    @Override
    public String execute(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();
        if (userQuery == null || userQuery.isEmpty()) {
            userQuery = ctx.getVariable("userQuery");
        }
        log.info("RouterAgent classifying query: {}", userQuery);

        String intent;

        if (isChitchat(userQuery)) {
            log.info("RouterAgent: rule-based chitchat detection matched");
            intent = "chitchat";
        } else {
            String prompt = String.format(INTENT_PROMPT, userQuery);
            String response = llm.chat(prompt).trim();
            log.info("RouterAgent LLM response: {}", response);

            if (response.startsWith("multi_intent")) {
                List<SubTask> subTasks = parseSubTasks(response);
                List<String> subQueries = subTasks.stream().map(SubTask::query).toList();

                ctx.setVariable("intent", "multi_intent");
                ctx.setVariable("subTasks", subTasks);
                ctx.setVariable("subQueries", subQueries);
                log.info("RouterAgent classified as multi_intent with {} subtasks", subTasks.size());

                ctx.addMessage(Message.user(userQuery));
                return "multi_intent";
            }

            intent = normalizeIntent(response);
        }

        ctx.setVariable("intent", intent);
        ctx.setVariable("subQueries", List.of(userQuery));
        log.info("RouterAgent classified as: {}", intent);

        ctx.addMessage(Message.user(userQuery));
        return intent;
    }

    private boolean isChitchat(String query) {
        String lower = query.toLowerCase().trim();
        for (String keyword : CHITCHAT_KEYWORDS) {
            if (lower.equals(keyword.toLowerCase()) || lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化意图分类结果，将原始文本转换为意图标签。
     * <p>
     * 如果原始文本包含 "knowledge" 或 "qa"，归类为 knowledge_qa。
     * 如果包含 "chitchat" 或 "闲聊"，归类为 chitchat。
     * 如果包含 "weather" 或 "天气"，归类为 weather。
     * 如果包含 "ingestion" 或 "doc" 或 "文档"，归类为 doc_ingestion。
     * 否则，归类为 knowledge_qa。
     *
     * @param raw 原始意图分类结果
     * @return 归一化后的意图标签
     */
    //根据大模型的输出，，识别出用户的意图
    private String normalizeIntent(String raw) {
        String trimmed = raw.strip().toLowerCase();
        if (trimmed.contains("knowledge") || trimmed.contains("qa")) {
            return "knowledge_qa";
        }
        if (trimmed.contains("chitchat") || trimmed.contains("闲聊")) {
            return "chitchat";
        }
        if (trimmed.contains("weather") || trimmed.contains("天气")) {
            return "weather";
        }
        if (trimmed.contains("ingestion") || trimmed.contains("doc") || trimmed.contains("文档")) {
            return "doc_ingestion";
        }
        return "knowledge_qa";
    }

    /**
     * 解析多意图分类结果，提取子任务。
     * <p>
     * 从 LLM 输出中提取 "- intent:query" 格式的子任务，每个子任务对应一个 SubTask。
     * 如果没有子任务，返回默认子任务 "knowledge_qa:用户输入"。
     *
     * @param response LLM 输出的多意图分类结果
     * @return 解析后的子任务列表
     */
    private List<SubTask> parseSubTasks(String response) {
        List<SubTask> tasks = new ArrayList<>();
        Matcher matcher = SUB_TASK_PATTERN.matcher(response);// 匹配 "- intent:query" 格式的子任务
        while (matcher.find()) {// 查找所有匹配的子任务
            String intent = normalizeIntent(matcher.group(1));// 归一化子任务意图
            String query = matcher.group(2).trim();// 提取子任务查询
            if (!query.isEmpty()) {
                tasks.add(new SubTask(intent, query));
            }
        }
        return tasks.isEmpty() ? List.of(new SubTask("knowledge_qa", "")) : tasks;
    }
}

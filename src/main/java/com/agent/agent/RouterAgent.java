package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
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

            1. knowledge_qa：需要从知识库检索文档来回答问题（如：询问公司制度、财务数据、技术文档等）
            2. chitchat：闲聊、问候、通用问题，不需要检索文档
            3. doc_ingestion：涉及文档上传、入库、处理等操作
            4. multi_intent：包含多个独立子任务，需要分别处理

            分类规则：
            - 如果用户只是打招呼、闲聊，归类为 chitchat
            - 如果用户询问需要查资料才能回答的问题，归类为 knowledge_qa
            - 如果用户提到上传、导入、处理文档，归类为 doc_ingestion
            - 如果用户一次性提出多个不同类型的问题，归类为 multi_intent，并拆解子任务

            用户输入：%s

            输出格式（单选）：
            单意图：直接回复意图标签（knowledge_qa / chitchat / doc_ingestion）
            多意图：回复 multi_intent 并另起一行列出子任务，每行格式：- intent:query
            """;

    // 子任务正则表达式，匹配 "- intent:query" 格式
    private static final Pattern SUB_TASK_PATTERN = Pattern.compile("^\\s*-\\s*(\\w+)\\s*:\\s*(.+)$", Pattern.MULTILINE);

    private final DeepSeekChatClient llm;

    public RouterAgent(DeepSeekChatClient llm) {
        this.llm = llm;
    }

    @Override
    public String name() {
        return "router";// 意图识别 Agent 名称
    }

    @Override
    public String execute(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();// 用户输入
        if (userQuery == null || userQuery.isEmpty()) {
            userQuery = ctx.getVariable("userQuery");// 从 context.variables 读取用户输入
        }
        log.info("RouterAgent classifying query: {}", userQuery);

        String prompt = String.format(INTENT_PROMPT, userQuery);// 构建意图分类提示
        log.info("RouterAgent prompt: {}", prompt);
        //读取用户的输入，调用 LLM 大模型来进行意图识别
        String response = llm.chat(prompt).trim();// 调用 LLM 获取意图分类结果
        log.info("RouterAgent response: {}", response);

        if (response.startsWith("multi_intent")) {// 多意图，有多个子任务
            // 解析子任务，每个子任务对应一个 SubTask，然后添加到集合中
            List<SubTask> subTasks = parseSubTasks(response);
            //其实是一条冗余数据，因为 subTasks 已经包含了子任务查询，所以这里可以省略
            List<String> subQueries = subTasks.stream().map(SubTask::query).toList();// 提取子任务查询

            ctx.setVariable("intent", "multi_intent");
            ctx.setVariable("subTasks", subTasks);
            ctx.setVariable("subQueries", subQueries);
            log.info("RouterAgent classified as multi_intent with {} subtasks", subTasks.size());
        } else {// 单意图，没有子任务
            String intent = normalizeIntent(response);//用户意图的识别
            ctx.setVariable("intent", intent);
            ctx.setVariable("subQueries", List.of(userQuery));//哪怕只有一个任务，也将其添加到集合中，后边OrchestratorAgent就只需要遍历集合就行了，不需要单独讨论单意图的情况
            log.info("RouterAgent classified as: {}", intent);
        }

        ctx.addMessage(Message.user(userQuery));
        return ctx.getVariable("intent");// 返回意图标签
    }

    /**
     * 归一化意图分类结果，将原始文本转换为意图标签。
     * <p>
     * 如果原始文本包含 "knowledge" 或 "qa"，归类为 knowledge_qa。
     * 如果包含 "chitchat" 或 "闲聊"，归类为 chitchat。
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

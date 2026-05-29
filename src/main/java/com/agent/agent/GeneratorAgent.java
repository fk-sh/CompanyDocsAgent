package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import com.agent.llm.DeepSeekStreamingClient;
import com.agent.memory.preference.PreferenceDocument;
import com.agent.memory.preference.PreferenceMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component("generatorAgent")
public class GeneratorAgent implements Agent {

    private static final String QA_SYSTEM_PROMPT = """
            你是一个专业的知识库问答助手。你必须严格基于用户提供的检索文档内容回答问题。

            === 输出格式铁律（必须逐条遵守，违反任何一条即为不合格） ===

            1. 【空行分段】每个标题前后必须有空行，每个代码块前后必须有空行，每个段落之间必须有空行。
               正确格式：
               ## 标题

               内容段落...

               ```java
               代码...
               ```

            2. 【标题格式】只用 ## 二级标题。标题后面必须有空格再接文字。

            3. 【代码强制格式化】检索文档中代码 token 之间可能没有空格，你必须在输出时修复。
               - 类型和变量名之间加空格
               - 操作符两侧加空格
               - 每条语句独占一行
               - 用 ```java 和 ``` 包裹整个代码块
               - 代码块前后必须各有一个空行

            4. 【表格格式】必须使用标准 Markdown 表格，严格遵守以下示例：

               ✅ 正确（独立成块，前后有空行）：

               | 区域 | 特性 | 作用 |
               | :--- | :--- | :--- |
               | 程序计数器 | 线程私有 | 记录执行位置 |
               | 虚拟机栈 | 线程私有 | 存储栈帧 |

               ❌ 错误（禁止内嵌在段落中）：
               ...用于存储不同类别的数据 | 区域 | 特性 | 作用 ||:---|...

               规则：表格必须独占多行，表头行 + 分隔行 + 数据行，绝对不能把表格写在一段文字里面。

               
            5. 【引用格式】在相关内容后标注来源。
               例：...性能开销 [来源 1 - xxx.docx]。每个事实陈述后都要标注来源，不要遗漏。

            6. 【去重】同一知识点只出现一次，禁止重复。

            7. 【绝对禁止】绝对不能编造信息。只能基于检索文档中的内容回答。
            """;

    private static final String CHITCHAT_SYSTEM_PROMPT = """
            你是一个友好的智能助手。请用自然、亲切的语气回复用户。
            """;

    private static final String MULTI_INTENT_SUMMARY_PROMPT = """
            你是一个专业的答案汇总助手。请将以下多个子任务的回答整合为一个条理清晰的统一回复。

            【用户原始问题】
            %s

            【各子任务回答】
            %s

            要求：
            1. 整合各子任务的结果，避免重复
            2. 结构清晰，用 ## 二级标题分段呈现不同子问题的答案
            3. 保留引用来源标注
            4. 天气类答案放在最前面
            """;

    private static final String REFLECTION_PROMPT = """
            你是一个严格的质量审核员。请审核以下回答是否达标。

            【用户问题】
            %s

            【回答】
            %s

            审核标准：
            1. 回答是否直接回应了用户问题？是否存在答非所问？
            2. 回答是否清晰、完整、无歧义？
            3. 回答是否不存在明显的事实错误或编造？

            请只回复一个单词：PASS 或 FAIL
            如果 FAIL，请另起一行简要说明问题所在。
            """;

    private static final int MAX_REFLECTION_ROUNDS = 1;

    private final DeepSeekChatClient llm;
    private final DeepSeekStreamingClient streamingClient;
    private final PreferenceMemoryStore preferenceStore;

    public GeneratorAgent(DeepSeekChatClient llm, DeepSeekStreamingClient streamingClient,
                          PreferenceMemoryStore preferenceStore) {
        this.llm = llm;
        this.streamingClient = streamingClient;
        this.preferenceStore = preferenceStore;
    }

    @Override
    public String name() {
        return "generator";
    }

    public String generate(AgentContext ctx) {
        String intent = ctx.getVariable("intent", "knowledge_qa");

        if ("multi_intent".equals(intent) || isMultiIntentSummary(ctx)) {
            return generateMultiIntentSummary(ctx);
        }

        if ("chitchat".equals(intent)) {
            return generateChitchat(ctx);
        }

        return generateWithReflection(ctx);
    }

    public Flux<String> generateStream(AgentContext ctx) {
        String intent = ctx.getVariable("intent", "knowledge_qa");

        String systemPrompt;
        String userPrompt;

        if ("multi_intent".equals(intent) || isMultiIntentSummary(ctx)) {
            systemPrompt = QA_SYSTEM_PROMPT;
            userPrompt = buildMultiIntentUserPrompt(ctx);
        } else if ("chitchat".equals(intent)) {
            systemPrompt = CHITCHAT_SYSTEM_PROMPT;
            userPrompt = buildChitchatUserPrompt(ctx);
        } else {
            systemPrompt = QA_SYSTEM_PROMPT;
            userPrompt = buildKnowledgeUserPrompt(ctx);
        }

        return streamingClient.streamRaw(systemPrompt, userPrompt)
                .collectList()
                .map(tokens -> formatAnswer(String.join("", tokens)))
                .flatMapMany(Flux::just)
                .doOnComplete(() -> log.info("GeneratorAgent stream completed"))
                .doOnError(e -> log.error("GeneratorAgent stream error: {}", e.getMessage()));
    }

    private String generateWithReflection(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();
        String userPrompt = buildKnowledgeUserPrompt(ctx);

        String answer = llm.chat(QA_SYSTEM_PROMPT, userPrompt);
        log.info("GeneratorAgent first answer generated, length={}", answer.length());

        for (int round = 1; round <= MAX_REFLECTION_ROUNDS; round++) {
            String reflectionPrompt = String.format(REFLECTION_PROMPT, userQuery, answer);
            String reflectionResult = llm.chat(reflectionPrompt).trim();
            String upper = reflectionResult.toUpperCase();

            if (upper.contains("PASS") && !upper.contains("FAIL")) {
                log.info("GeneratorAgent reflection PASS at round {}", round);
                return formatAnswer(answer);
            }

            log.info("GeneratorAgent reflection FAIL at round {}/{}: {}", round, MAX_REFLECTION_ROUNDS, reflectionResult);

            if (round < MAX_REFLECTION_ROUNDS) {
                String critique = extractCritique(reflectionResult);
                String regeneratePrompt = buildRegeneratePrompt(userPrompt, critique);
                answer = llm.chat(QA_SYSTEM_PROMPT, regeneratePrompt);
                log.info("GeneratorAgent regenerated answer, length={}", answer.length());
            }
        }

        log.warn("GeneratorAgent reflection failed after {} rounds, outputting last version", MAX_REFLECTION_ROUNDS);
        return formatAnswer(answer);
    }

    private String generateChitchat(AgentContext ctx) {
        String userPrompt = buildChitchatUserPrompt(ctx);
        return formatAnswer(llm.chat(CHITCHAT_SYSTEM_PROMPT, userPrompt));
    }

    private String generateMultiIntentSummary(AgentContext ctx) {
        String userPrompt = buildMultiIntentUserPrompt(ctx);
        return formatAnswer(generateWithReflectionInternal(userPrompt));
    }

    private String generateWithReflectionInternal(String userPrompt) {
        String answer = llm.chat(QA_SYSTEM_PROMPT, userPrompt);

        for (int round = 1; round <= MAX_REFLECTION_ROUNDS; round++) {
            String reflectionPrompt = String.format(REFLECTION_PROMPT, "多意图汇总", answer);
            String reflectionResult = llm.chat(reflectionPrompt).trim();
            String upper = reflectionResult.toUpperCase();

            if (upper.contains("PASS") && !upper.contains("FAIL")) {
                return answer;
            }

            if (round < MAX_REFLECTION_ROUNDS) {
                String critique = extractCritique(reflectionResult);
                answer = llm.chat(QA_SYSTEM_PROMPT, buildRegeneratePrompt(userPrompt, critique));
            }
        }
        return answer;
    }

    private boolean isMultiIntentSummary(AgentContext ctx) {
        String subAnswers = ctx.getVariable("subAnswers", "");
        return !subAnswers.isEmpty();
    }

    private String buildKnowledgeUserPrompt(AgentContext ctx) {
        String retrievedContext = ctx.getVariable("retrievedContext", "");
        String userQuery = ctx.getUserQuery();
        String memoryContext = ctx.getVariable("memoryContext", "");
        String userPreferences = buildUserPreferencesText(ctx);
        String episodicContext = ctx.getVariable("episodicContext", "");

        String answerMode = selectAnswerMode(userQuery);

        StringBuilder sb = new StringBuilder();
        if (!userPreferences.isEmpty() && !userPreferences.equals("（暂无用户偏好记录）")) {
            sb.append("【用户偏好】\n").append(userPreferences).append("\n\n");
        }
        if (!episodicContext.isEmpty()) {
            sb.append("【相关情景记忆】\n").append(episodicContext).append("\n\n");
        }
        if (!memoryContext.isEmpty()) {
            sb.append("【历史上下文】\n").append(memoryContext).append("\n\n");
        }
        if (!retrievedContext.isEmpty()) {
            sb.append("【检索到的相关文档内容】\n").append(retrievedContext).append("\n\n");
        }
        sb.append("【用户问题】\n").append(userQuery).append("\n\n");
        if (!answerMode.isEmpty()) {
            sb.append(answerMode).append("\n\n");
        }
        sb.append("【重要提醒】请严格按照 System Prompt 中的格式规则输出。");
        return sb.toString();
    }

    private String buildChitchatUserPrompt(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();
        String memoryContext = ctx.getVariable("memoryContext", "");
        String userPreferences = buildUserPreferencesText(ctx);
        String episodicContext = ctx.getVariable("episodicContext", "");

        StringBuilder sb = new StringBuilder();
        if (!userPreferences.isEmpty() && !userPreferences.equals("（暂无用户偏好记录）")) {
            sb.append("【用户偏好】\n").append(userPreferences).append("\n\n");
        }
        if (!episodicContext.isEmpty()) {
            sb.append("【相关情景记忆】\n").append(episodicContext).append("\n\n");
        }
        if (!memoryContext.isEmpty()) {
            sb.append("【历史上下文】\n").append(memoryContext).append("\n\n");
        }
        sb.append("【用户消息】\n").append(userQuery);
        return sb.toString();
    }

    private String buildMultiIntentUserPrompt(AgentContext ctx) {
        String userQuery = ctx.getUserQuery();
        String subAnswers = ctx.getVariable("subAnswers", "");
        return String.format(MULTI_INTENT_SUMMARY_PROMPT, userQuery, subAnswers);
    }

    private String buildRegeneratePrompt(String originalPrompt, String critique) {
        return originalPrompt + "\n\n【上一版的问题（请针对性改进）】\n" + critique;
    }

    private String extractCritique(String reflectionResult) {
        int idx = reflectionResult.indexOf('\n');
        if (idx > 0) {
            return reflectionResult.substring(idx + 1).trim();
        }
        return "回答未能通过质量审核，请改进。";
    }

    private static final String NL = "\u2028";

    private String formatAnswer(String raw) {
        String text = raw.trim();

        java.util.LinkedHashMap<Integer, String> sources = extractSources(text);
        text = removeAllSourceMarkers(text);

        text = fixInlineTables(text);

        text = text.replaceAll("(?m)^##\\s*", NL + NL + "## ");
        text = text.replaceAll("(?m)^###\\s*", NL + NL + "### ");

        text = text.replaceAll("(?m)^(\\d+[.、]\\s*)", NL + "$1");
        text = text.replaceAll("(?m)^([-*])\\s+", NL + "$1 ");

        text = text.replaceAll("(?<!" + NL + ")(?=```)", NL + NL);
        text = text.replaceAll("(```)(?!\\s*$)", "$1" + NL);

        text = text.replaceAll(NL + "{3,}", NL + NL);
        text = text.trim();

        if (!sources.isEmpty()) {
            StringBuilder srcBlock = new StringBuilder(NL).append(NL).append("---").append(NL).append("**📚 参考来源**").append(NL);
            for (java.util.Map.Entry<Integer, String> entry : sources.entrySet()) {
                srcBlock.append("- ").append(entry.getValue()).append(NL);
            }
            text += srcBlock.toString();
        }

        return text;
    }

    private java.util.LinkedHashMap<Integer, String> extractSources(String text) {
        java.util.LinkedHashMap<Integer, String> sources = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\\[来源\\s*(\\d+)\\s*[-:：\\u2014\\u2013]+\\s*([^\\]]+)?\\]"
        );
        java.util.regex.Matcher m = p.matcher(text);
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String fileName = m.group(2);
            if (fileName != null) fileName = fileName.trim();
            String label = (fileName != null && !fileName.isEmpty())
                ? "来源 " + idx + " - " + fileName : "来源 " + idx;
            sources.putIfAbsent(idx, label);
        }

        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("\\[来源\\s*(\\d+)\\]");
        java.util.regex.Matcher m2 = p2.matcher(text);
        while (m2.find()) {
            int idx = Integer.parseInt(m2.group(1));
            sources.putIfAbsent(idx, "来源 " + idx);
        }
        return sources;
    }

    private String removeAllSourceMarkers(String text) {
        return text.replaceAll("\\s*\\[来源\\s*\\d+(?:\\s*[-:：\\u2014\\u2013]+\\s*[^\\]]*)?\\]\\s*", "");
    }

    private String fixInlineTables(String text) {
        if (!text.contains("|") || !text.contains(":---")) return text;

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(\\|[^|\n]+\\|)(?:\\s*\\|\\s*)?[|:\\-\\s]{3,}(?:\\s*\\|\\s*)?((?:\\|\\|?[^|\n]*)+)"
        );

        java.util.regex.Matcher m = p.matcher(text);
        if (!m.find()) return text;

        m.reset();
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rawHeader = m.group(1);
            String rawRest = m.group(2);

            java.util.List<String> headers = parsePipeCells(rawHeader);
            if (headers.size() < 2) {
                m.appendReplacement(sb, m.group(0));
                continue;
            }

            java.util.List<String[]> dataRows = new java.util.ArrayList<>();
            java.util.List<String> currentRow = new java.util.ArrayList<>();
            String[] segments = rawRest.split("\\|\\|");
            for (String seg : segments) {
                seg = seg.trim();
                if (seg.isEmpty() || seg.matches("[\\s:\\-]+")) {
                    if (!currentRow.isEmpty()) {
                        dataRows.add(currentRow.toArray(new String[0]));
                        currentRow = new java.util.ArrayList<>();
                    }
                    continue;
                }
                java.util.List<String> cells = parsePipeCells(seg);
                currentRow.addAll(cells);
            }
            if (!currentRow.isEmpty()) {
                dataRows.add(currentRow.toArray(new String[0]));
            }

            int colCount = Math.min(headers.size(), 4);
            StringBuilder fixed = new StringBuilder(NL).append(NL);

            fixed.append("| ");
            for (int i = 0; i < colCount; i++) fixed.append(headers.get(i)).append(i < colCount - 1 ? " | " : " |");
            fixed.append(NL).append("| ");
            for (int i = 0; i < colCount; i++) fixed.append(":---").append(i < colCount - 1 ? " | " : " |");
            fixed.append(NL);

            for (String[] row : dataRows) {
                fixed.append("| ");
                for (int i = 0; i < colCount && i < row.length; i++) {
                    fixed.append(row[i]).append(i < colCount - 1 ? " | " : " |");
                }
                fixed.append(NL);
            }
            fixed.append(NL);
            m.appendReplacement(sb, fixed.toString());
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private java.util.List<String> parsePipeCells(String pipeLine) {
        java.util.List<String> cells = new java.util.ArrayList<>();
        String[] parts = pipeLine.split("\\|");
        for (String part : parts) {
            part = part.trim();
            if (!part.isEmpty()) cells.add(part);
        }
        return cells;
    }

    private String selectAnswerMode(String userQuery) {
        if (userQuery == null) {
            return "";
        }
        String lower = userQuery.toLowerCase();
        if (lower.contains("区别") || lower.contains("对比") || lower.contains("比较")) {
            return "【回答模式：对比分析】用 Markdown 表格对比差异。";
        }
        if (lower.contains("示例") || lower.contains("代码") || lower.contains("怎么用")) {
            return "【回答模式：代码示例】给出格式化代码并解释。";
        }
        if (lower.contains("是什么") || lower.contains("概念") || lower.contains("什么是")) {
            return "【回答模式：概念解释】先给定义再展开。";
        }
        return "";
    }

    private String buildUserPreferencesText(AgentContext ctx) {
        String userId = ctx.getVariable("userId", "anonymous");
        List<PreferenceDocument> all = preferenceStore.findAll(userId);
        if (all.isEmpty()) {
            return "（暂无用户偏好记录）";
        }
        return all.stream().map(PreferenceDocument::getContent)
                .map(p -> "- " + p).collect(Collectors.joining("\n"));
    }

    @Override
    public String execute(AgentContext ctx) {
        return "generator agent executed";
    }
}

package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import com.agent.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component("weatherAgent")
public class WeatherAgent implements Agent {

    private static final String WEATHER_ADVICE_PROMPT = """
            你是一个贴心的生活助手。请严格基于以下天气数据，为用户提供专业建议。

            【天气数据】
            %s

            【格式要求 - 必须严格遵守】
            1. 每个章节标题必须独占一行
            2. 标题前后各留一个空行（即标题上下都是空行）
            3. 每个要点单独一行，用 - 或数字编号开头
            4. 段落之间用空行分隔
            5. 绝对不要把所有内容写成一段话

            【输出模板 - 请严格按此结构输出】

            🌤️ 今日天气

            （1-2句话概括，标注温度、风力、湿度）

            👔 穿搭建议

            - 衣物厚度建议
            - 防风/防雨建议
            - 配色建议

            🚗 出行建议

            - 出行适宜度评级
            - 风险提醒
            - 推荐活动

            💊 健康提醒

            - 紫外线/防潮/温差等提醒
            - 季节性注意事项

            请用亲切自然的语气。再次强调：每个标题独占一行，标题前后有空行，不要挤在一起！
            """;

    private final McpTool weatherTool;
    private final DeepSeekChatClient llm;

    public WeatherAgent(List<McpTool> tools, DeepSeekChatClient llm) {
        this.weatherTool = tools.stream()
                .filter(t -> "weather_query".equals(t.name()))
                .findFirst()
                .orElse(null);
        this.llm = llm;
        log.info("WeatherAgent initialized, weatherTool available: {}", weatherTool != null);
    }

    @Override
    public String name() {
        return "weather";
    }

    public String query(String city) {
        if (weatherTool == null) {
            return "天气查询服务暂时不可用";
        }

        log.info("WeatherAgent querying weather for: {}", city);

        String rawWeather;
        try {
            var result = weatherTool.call(Map.of("city", city));
            if (result.content() != null && !result.content().isEmpty()) {
                Content c = result.content().get(0);
                rawWeather = c instanceof TextContent tc ? tc.text() : c.toString();
            } else {
                rawWeather = "天气数据获取失败";
            }
        } catch (Exception e) {
            log.error("WeatherAgent MCP call failed: {}", e.getMessage());
            rawWeather = "天气数据获取失败: " + e.getMessage();
        }

        if (rawWeather.contains("失败") || rawWeather.isEmpty()) {
            return "抱歉，未能获取到 " + city + " 的天气数据，请稍后重试。";
        }

        log.info("WeatherAgent raw weather for {}: {}", city, rawWeather);

        String advicePrompt = String.format(WEATHER_ADVICE_PROMPT, rawWeather);

        try {
            String advice = llm.chat(advicePrompt);
            log.info("WeatherAgent generated advice for {}: {} chars", city, advice.length());
            return formatAdvice(advice);
        } catch (Exception e) {
            log.error("WeatherAgent LLM advice generation failed: {}", e.getMessage());
            return rawWeather + "\n\n（智能建议生成失败，以上为原始天气数据）";
        }
    }

    private static final String[] TITLE_MARKERS = {
            "🌤️ 今日天气", "👔 穿搭建议", "🚗 出行建议", "💊 健康提醒",
            "## 🌤️", "## 👔", "## 🚗", "## 💊",
            "今日天气", "穿搭建议", "出行建议", "健康提醒"
    };

    private static final String NL = "\u2028";

    private String formatAdvice(String raw) {
        String text = raw.trim();

        for (String marker : TITLE_MARKERS) {
            if (text.contains(marker)) {
                text = text.replace(marker, NL + NL + marker);
            }
        }

        text = text.replaceAll("(?m)^(\\d+[.、]\\s*)", NL + "$1");
        text = text.replaceAll("(?m)^([-*])\\s+", NL + "$1 ");
        text = text.replaceAll(NL + "{3,}", NL + NL);

        return text;
    }

    @Override
    public String execute(AgentContext ctx) {
        return "weather agent executed";
    }
}

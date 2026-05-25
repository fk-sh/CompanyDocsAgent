package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.AgentSkill;
import com.agent.core.AgentSkill.VariableDef;
import com.agent.core.Message;
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

    private final McpTool weatherTool;

    public WeatherAgent(List<McpTool> tools) {
        this.weatherTool = tools.stream()
                .filter(t -> "weather_query".equals(t.name()))
                .findFirst()
                .orElse(null);
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public AgentSkill skill() {
        return new AgentSkill(
                "weather",
                "天气查询：通过 MCP 协议调用 weather_query 工具获取指定城市的实时天气信息",
                List.of(
                        VariableDef.optionalInput("userQuery", "String", "城市名称，如：北京、上海")
                ),
                List.of(
                        VariableDef.output("answer", "String", "格式化后的天气信息文本"),
                        VariableDef.output("finalAnswer", "String", "最终答案（同 answer）")
                )
        );
    }

    @Override
    public String execute(AgentContext ctx) {
        String city = extractCity(ctx.getUserQuery());
        if (city == null || city.isEmpty()) {
            city = ctx.getVariable("userQuery", "北京");
        }
        if (city.length() > 10) {
            city = "北京";
        }

        log.info("WeatherAgent calling MCP tool weather_query for: {}", city);

        String result;
        if (weatherTool != null) {
            var callResult = weatherTool.call(Map.of("city", city));
            if (!callResult.content().isEmpty()) {
                Content c = callResult.content().get(0);
                result = c instanceof TextContent tc ? tc.text() : c.toString();
            } else {
                result = "天气查询无结果";
            }
        } else {
            result = "天气查询工具未注册";
        }

        ctx.setVariable("answer", result);
        ctx.setVariable("finalAnswer", result);
        ctx.addMessage(Message.assistant(result));

        log.info("WeatherAgent completed: {}", result);
        return result;
    }

    private String extractCity(String query) {
        if (query == null || query.isEmpty()) {
            return "北京";
        }
        String[] cities = {"北京", "上海", "深圳", "广州", "杭州", "成都", "武汉",
                "南京", "天津", "重庆", "西安", "长沙", "青岛", "大连", "厦门",
                "苏州", "郑州", "沈阳", "哈尔滨", "昆明"};
        for (String city : cities) {
            if (query.contains(city)) {
                return city;
            }
        }
        return "北京";
    }
}

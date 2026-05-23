package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.AgentSkill;
import com.agent.core.AgentSkill.VariableDef;
import com.agent.core.Message;
import com.agent.mcp.McpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component("weatherAgent")
public class WeatherAgent implements Agent {

    private final McpClient mcpClient;

    public WeatherAgent(McpClient mcpClient) {
        this.mcpClient = mcpClient;
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
        String city = ctx.getUserQuery();
        if (city == null || city.isEmpty()) {
            city = ctx.getVariable("userQuery", "北京");
        }

        log.info("WeatherAgent calling MCP tool weather_query for: {}", city);
        String result = mcpClient.callTool("weather_query", Map.of("city", city));

        ctx.setVariable("answer", result);
        ctx.setVariable("finalAnswer", result);
        ctx.addMessage(Message.assistant(result));

        log.info("WeatherAgent completed: {}", result);
        return result;
    }
}

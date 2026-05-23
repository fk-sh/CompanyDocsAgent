package com.agent.agent;

import com.agent.core.Agent;
import com.agent.core.AgentContext;
import com.agent.core.Message;
import com.agent.mcp.McpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

package com.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * 天气查询 MCP 工具，遵循 MCP 协议。
 * <p>
 * 使用 wttr.in 免费天气 API，不需要 API Key。
 * 通过 MCP 的 inputSchema 声明参数规范，LLM 可精确理解如何调用。
 */
@Slf4j
@Component
public class WeatherMcpTool implements McpTool {

    private static final String WEATHER_API_URL = "https://wttr.in/";

    private final WebClient webClient;

    public WeatherMcpTool(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    @PostConstruct
    public void init() {
        log.info("WeatherMcpTool registered: {}", name());
    }

    @Override
    public String name() {
        return "weather_query";
    }

    @Override
    public String description() {
        return "查询指定城市的实时天气信息，包括天气状况、温度、风速、湿度。支持中文城市名。";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "city": {
                      "type": "string",
                      "description": "要查询的城市名称，支持中文（如：北京、上海、深圳）"
                    }
                  },
                  "required": ["city"]
                }""";
    }

    @Override
    public McpToolResult call(Map<String, Object> arguments) {
        String city = (String) arguments.getOrDefault("city", "北京");

        try {
            String encodedCity = java.net.URLEncoder.encode(city, "UTF-8");
            String url = WEATHER_API_URL + encodedCity + "?format=%C+%t+%w+%h&lang=zh";

            // 发送 HTTP 请求获取天气信息
            String result = webClient.get()
                    .uri(url)// 构建请求 URL
                    .retrieve()// 发送请求
                    .bodyToMono(String.class)// 转换为 Mono 字符串
                    .block();// 阻塞等待响应

            String weather = result != null ? result.strip() : "查询无结果";
            log.info("WeatherMcpTool: {} → {}", city, weather);
            return McpToolResult.success(city + "天气：" + weather);// 返回天气信息

        } catch (Exception e) {
            log.error("WeatherMcpTool failed for {}: {}", city, e.getMessage());
            return McpToolResult.error("天气查询失败：" + e.getMessage());
        }
    }
}

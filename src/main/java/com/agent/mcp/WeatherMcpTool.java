package com.agent.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

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
    public CallToolResult call(Map<String, Object> arguments) {
        String city = String.valueOf(arguments.getOrDefault("city", "北京")).trim();

        for (String candidate : buildCityCandidates(city)) {
            try {
                String encodedCity = java.net.URLEncoder.encode(candidate, "UTF-8");
                String url = WEATHER_API_URL + encodedCity + "?format=%C+%t+%w+%h&lang=zh";

                String result = webClient.get()
                        .uri(url)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                String weather = result != null ? result.strip() : "";
                if (isValidWeather(weather)) {
                    log.info("WeatherMcpTool: {}({}) → {}", city, candidate, weather);
                    return success("【" + city + "天气】" + weather);
                }
                log.warn("WeatherMcpTool invalid result for {}({}): {}", city, candidate, weather);
            } catch (Exception e) {
                log.warn("WeatherMcpTool failed for {}({}): {}", city, candidate, e.getMessage());
            }
        }

        return error("天气查询失败：无法获取 " + city + " 的天气数据");
    }

    private java.util.List<String> buildCityCandidates(String city) {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(city);
        candidates.add(city.replace("市", ""));

        String alias = cityAlias(city.replace("市", ""));
        if (!alias.isEmpty()) {
            candidates.add(alias);
        }
        return new java.util.ArrayList<>(candidates);
    }

    private String cityAlias(String city) {
        return switch (city) {
            case "西安" -> "xian";
            case "北京" -> "beijing";
            case "上海" -> "shanghai";
            case "广州" -> "guangzhou";
            case "深圳" -> "shenzhen";
            case "杭州" -> "hangzhou";
            case "南京" -> "nanjing";
            case "成都" -> "chengdu";
            case "重庆" -> "chongqing";
            case "武汉" -> "wuhan";
            case "天津" -> "tianjin";
            case "苏州" -> "suzhou";
            case "郑州" -> "zhengzhou";
            case "长沙" -> "changsha";
            default -> "";
        };
    }

    private boolean isValidWeather(String weather) {
        if (weather == null || weather.isBlank()) {
            return false;
        }
        String lower = weather.toLowerCase();
        return !lower.contains("unknown location")
                && !lower.contains("not found")
                && !lower.contains("sorry")
                && !weather.contains("查询无结果");
    }
}

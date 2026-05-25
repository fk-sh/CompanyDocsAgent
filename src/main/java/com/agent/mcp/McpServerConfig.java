package com.agent.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Slf4j
@Configuration
public class McpServerConfig {

    @Bean
    public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
        return new JacksonMcpJsonMapper(objectMapper);
    }

    @Bean
    public HttpServletSseServerTransportProvider mcpTransportProvider(McpJsonMapper jsonMapper) {
        return HttpServletSseServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .sseEndpoint("/api/mcp/sse")
                .messageEndpoint("/api/mcp/message")
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletSseServerTransportProvider> mcpServletRegistration(
            HttpServletSseServerTransportProvider transportProvider) {
        ServletRegistrationBean<HttpServletSseServerTransportProvider> bean =
                new ServletRegistrationBean<>(transportProvider);
        bean.addUrlMappings("/api/mcp/sse", "/api/mcp/message");
        return bean;
    }

    @Bean
    public McpSyncServer mcpSyncServer(HttpServletSseServerTransportProvider transportProvider,
                                        List<McpTool> tools,
                                        McpJsonMapper jsonMapper) {
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("agent-kb-qa-mcp", "1.0.0")
                .capabilities(ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .build();

        for (McpTool tool : tools) {
            try {
                SyncToolSpecification spec = SyncToolSpecification.builder()
                        .tool(Tool.builder()
                                .name(tool.name())
                                .description(tool.description())
                                .inputSchema(jsonMapper, tool.inputSchema())
                                .build())
                        .callHandler((exchange, request) -> tool.call(request.arguments()))
                        .build();
                server.addTool(spec);
                log.info("MCP tool registered: {}", tool.name());
            } catch (Exception e) {
                log.error("Failed to register MCP tool: {}", tool.name(), e);
            }
        }

        return server;
    }

    @PostConstruct
    public void init() {
        log.info("MCP Server configured with SSE transport");
    }
}

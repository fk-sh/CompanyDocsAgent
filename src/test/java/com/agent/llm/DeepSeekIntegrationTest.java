package com.agent.llm;

import com.agent.service.SimpleChatService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("LLM 接入层集成测试")
class DeepSeekIntegrationTest {

    @Autowired
    private DeepSeekConfig config;

    @Autowired
    private DeepSeekChatClient chatClient;

    @Autowired
    private DeepSeekStreamingClient streamingClient;

    @Autowired
    private SimpleChatService chatService;

    @Test
    @DisplayName("上下文加载成功，所有 Bean 正确注入")
    void contextLoads() {
        assertThat(config).isNotNull();
        assertThat(chatClient).isNotNull();
        assertThat(streamingClient).isNotNull();
        assertThat(chatService).isNotNull();
    }

    @Test
    @DisplayName("配置参数正确读取")
    void configProperties() {
        assertThat(config.getBaseUrl()).isEqualTo("https://api.deepseek.com/v1");
        assertThat(config.getModelName()).isEqualTo("deepseek-chat");
        assertThat(config.getTemperature()).isEqualTo(0.0);
        assertThat(config.getMaxTokens()).isEqualTo(128);
    }

    @Test
    @Tag("real-api")
    @DisplayName("真实 API 调用测试（需设置 DEEPSEEK_API_KEY 环境变量）")
    void realApiChat() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank() || "your-api-key".equals(apiKey)) {
            return;
        }

        String reply = chatService.chat("你好");
        assertThat(reply).isNotBlank();
        System.out.println("DeepSeek reply: " + reply);
    }
}

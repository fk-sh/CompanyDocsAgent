package com.agent.api;

import com.agent.service.SimpleChatService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestChatController {

    private final SimpleChatService chatService;

    public TestChatController(SimpleChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody Map<String, String> body) {
        String message = body.getOrDefault("message", "你好");
        String reply = chatService.chat(message);
        return Map.of("reply", reply);
    }

    @GetMapping("/chat")
    public Map<String, String> chatGet(@RequestParam(defaultValue = "你好") String message) {
        String reply = chatService.chat(message);
        return Map.of("reply", reply);
    }

    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> stream(@RequestParam(defaultValue = "你好") String message) {
        return chatService.stream(message);
    }
}

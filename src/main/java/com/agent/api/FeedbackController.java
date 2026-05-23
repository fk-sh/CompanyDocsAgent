package com.agent.api;

import com.agent.api.dto.FeedbackRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1")
public class FeedbackController {

    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@RequestBody FeedbackRequest request) {
        String feedbackId = UUID.randomUUID().toString().substring(0, 8);

        log.info("POST /feedback session={}, messageId={}, rating={}, comment={}",
                request.getSessionId(), request.getMessageId(),
                request.getRating(), request.getComment());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("feedbackId", feedbackId);
        result.put("sessionId", request.getSessionId());
        result.put("messageId", request.getMessageId());
        result.put("rating", request.getRating());
        result.put("status", "recorded");

        log.info("Feedback {} recorded: {}", feedbackId, result);
        return result;
    }
}

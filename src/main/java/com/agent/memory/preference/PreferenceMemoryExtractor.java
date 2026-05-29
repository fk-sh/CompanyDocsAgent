package com.agent.memory.preference;

import com.agent.llm.DeepSeekChatClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PreferenceMemoryExtractor {

    private static final String EXTRACT_PROMPT = """
            你是一个用户偏好提取器。分析用户的发言，从中提取出用户表达的偏好/属性/身份信息。

            ## 已有偏好（用于判断更新策略）
            {existingPreferences}

            ## 提取规则
            1. 每条偏好必须拆分为独立条目，不要合并。
               - 错误："职业和语言偏好：后端开发，Python, Go"
               - 正确：
                 - "职业：后端开发"
                 - "编程语言偏好：Python, Go"

            2. action 字段取值：
               - ADD：与已有偏好完全无关的新偏好
               - ACCUMULATE：在已有偏好基础上追加（如用户说"还学习Java"，关键词："还"、"以及"、"还有"、"也"）
               - REPLACE：覆盖已有偏好的值（如用户说"不喜欢红色了，喜欢蓝色"，关键词："不...了"、"改为"、"改成"、"换成"、"转成"）

            3. 格式要求：
               - content：自然语言完整描述，如"编程语言偏好：Python, Go, Java"
               - category：偏好的大类，如"职业"、"技术偏好"、"生活偏好"、"学习偏好"
               - key：唯一标识键（短小精悍），如"编程语言"、"职业"、"颜色偏好"
               - value：纯值部分，如"Python, Go, Java"、"后端开发"、"蓝色"

            4. 如果用户消息中不包含任何新的偏好信息，返回空数组。

            ## 输出格式（严格 JSON 数组）
            [
              {
                "content": "职业：后端开发",
                "category": "职业",
                "key": "职业",
                "value": "后端开发",
                "action": "ADD"
              }
            ]
            """;

    private final DeepSeekChatClient llm;
    private final ObjectMapper objectMapper;
    private final PreferenceMemoryStore store;

    public PreferenceMemoryExtractor(DeepSeekChatClient llm, ObjectMapper objectMapper,
                                      PreferenceMemoryStore store) {
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.store = store;
    }

    @Async
    public void extractAndStoreAsync(String userId, String userMessage) {
        try {
            List<PreferenceItem> items = extract(userId, userMessage);
            for (PreferenceItem item : items) {
                applyUpdate(userId, item);
            }
            if (!items.isEmpty()) {
                log.info("Extracted {} preference items for userId={}", items.size(), userId);
            }
        } catch (Exception e) {
            log.error("Preference extraction failed for userId={}: {}", userId, e.getMessage(), e);
        }
    }

    /**
     * 用 LLM 从用户消息中提取偏好列表。
     */
    public List<PreferenceItem> extract(String userId, String userMessage) {
        String existingPrefs = buildExistingPreferencesText(userId);
        String prompt = EXTRACT_PROMPT
                .replace("{existingPreferences}", existingPrefs);

        try {
            String response = llm.chat(prompt, userMessage);
            String json = extractJsonArray(response);
            if (json == null || json.isEmpty()) {
                return List.of();
            }

            List<Map<String, String>> rawList = objectMapper.readValue(
                    json, new TypeReference<List<Map<String, String>>>() {});

            return rawList.stream()
                    .map(m -> new PreferenceItem(
                            m.getOrDefault("content", ""),
                            m.getOrDefault("category", ""),
                            m.getOrDefault("key", ""),
                            m.getOrDefault("value", ""),
                            m.getOrDefault("action", "ADD")
                    ))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to parse preference extraction result: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 根据 action 类型决定存储策略：
     * - ADD：直接插入新偏好
     * - ACCUMULATE：找到已有偏好，追加 value
     * - REPLACE：删除旧偏好，插入新偏好
     */
    private void applyUpdate(String userId, PreferenceItem item) {
        List<PreferenceDocument> existing = store.findByKey(userId, item.key);

        switch (item.action) {
            case "ADD" -> store.insert(userId, item);
            case "ACCUMULATE" -> {
                if (!existing.isEmpty()) {
                    PreferenceDocument old = existing.get(0);
                    String oldValue = old.getValue();
                    String newValue = mergeValues(oldValue, item.value);
                    item.value = newValue;
                    item.content = item.key + "：" + newValue;
                    store.update(old.getId(), userId, item);
                    log.info("Accumulated preference: userId={}, key={}, {} -> {}", userId, item.key, oldValue, newValue);
                } else {
                    store.insert(userId, item);
                }
            }
            case "REPLACE" -> {
                store.deleteByKey(userId, item.key);
                store.insert(userId, item);
                log.info("Replaced preference: userId={}, key={}, value={}", userId, item.key, item.value);
            }
        }
    }

    /**
     * 智能合并新旧 value，去重追加。
     */
    private String mergeValues(String oldValue, String newValue) {
        String[] oldParts = oldValue.split("[,，、]");
        String[] newParts = newValue.split("[,，、]");

        List<String> merged = new ArrayList<>();
        for (String part : oldParts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                merged.add(trimmed);
            }
        }
        for (String part : newParts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !merged.contains(trimmed)) {
                merged.add(trimmed);
            }
        }
        return String.join(", ", merged);
    }

    private String buildExistingPreferencesText(String userId) {
        List<PreferenceDocument> all = store.findAll(userId);
        if (all.isEmpty()) {
            return "（暂无已有偏好）";
        }
        return all.stream()
                .map(p -> "- " + p.getContent() + " [key=" + p.getKey() + "]")
                .collect(Collectors.joining("\n"));
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }
}
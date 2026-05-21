package com.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.agent.core.Memory;
import com.agent.core.Message;
import com.agent.llm.DeepSeekChatClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户记忆层：跨会话提取并存储用户偏好（城市、语言、兴趣等），
 * 使新会话中 LLM 能自动利用用户画像回答问题。
 * <p>
 * <b>核心流程</b>：
 * <ol>
 *   <li><b>提取</b>：{@link #extractAndSave(String, String)} 将用户消息送给 LLM，
 *       提取结构化偏好信息（城市、语言、角色、兴趣等），存入 MySQL</li>
 *   <li><b>增量合并</b>：已有画像时，LLM 将新提取信息与旧画像合并，而非覆盖</li>
 *   <li><b>注入上下文</b>：{@link #buildUserContextPrompt(String)} 将画像转为文本，
 *       注入 AgentContext，LLM 生成回答时自动参考</li>
 * </ol>
 * <p>
 * <b>规则兜底</b>：LLM 提取失败时，本地正则规则（如"我在北京"→city=北京）作为兜底。
 * <p>
 * <b>使用场景示例</b>：
 * <pre>{@code
 *   用户第一次: "我是北京的程序员"
 *     → UserMemory 提取: {city:"北京", role:"程序员"}
 *   用户第二次(新会话): "今天天气怎么样？"
 *     → buildContext 注入: {userProfile: "用户所在城市:北京，职业:程序员"}
 *     → LLM 直接回答北京的天气
 * }</pre>
 *
 * @see UserProfile
 * @see UserProfileMapper
 */
@Slf4j
@Component
public class UserMemory implements Memory {

    private static final String EXTRACT_PROMPT = """
            你是一个用户信息提取助手。请从以下用户对话中提取关键个人信息。
            只提取明确提到的信息，不要猜测。

            ## 提取维度（仅提取对话中明确出现的信息，未提及的维度不要填）：
            1. city（城市/地区）
            2. language（偏好语言）
            3. role（职业/角色）
            4. interests（兴趣爱好，多个用json数组）
            5. tech_stack（技术栈，多个用json数组）

            ## 输出格式：纯JSON，不要任何额外文字
            {
              "city": "北京",
              "language": "中文",
              "role": "后端开发",
              "interests": ["篮球", "游戏"],
              "tech_stack": ["Java", "Spring Boot"]
            }

            用户消息：
            %s

            JSON输出：
            """;

    private static final String MERGE_PROMPT = """
            你是一个用户画像合并助手。请将新提取的信息合并到已有用户画像中。

            ## 合并规则：
            1. 已有画像中已存在的字段，如果新信息有更新则覆盖，否则保留
            2. 数组字段（interests/tech_stack）取并集去重
            3. 已有画像中独有的字段保留不变
            4. 新信息中独有的字段直接加入

            ## 已有画像：
            %s

            ## 新提取信息：
            %s

            ## 输出格式：纯JSON，不要任何额外文字
            合并后的画像JSON：
            """;

    private final UserProfileMapper profileMapper;
    private final DeepSeekChatClient chatClient;

    private final Map<String, String> profileCache = new ConcurrentHashMap<>();

    public UserMemory(UserProfileMapper profileMapper, DeepSeekChatClient chatClient) {
        this.profileMapper = profileMapper;
        this.chatClient = chatClient;
    }

    @Override
    public MemoryType type() {
        return MemoryType.LONG_TERM;
    }

    /**
     * 提取用户消息中的偏好信息并异步保存到 MySQL。
     * <p>
     * 只处理 USER 角色的消息；其他角色跳过。
     *
     * @param userId  用户 ID
     * @param content 用户消息正文
     */
    public void extractAndSaveAsync(String userId, String content) {
        if (content == null || content.length() < 4) {
            return;
        }
        try {
            String extracted = extractPreferences(content);
            if (extracted == null || extracted.isBlank()) {
                return;
            }

            UserProfile existing = loadProfile(userId);// 从数据库中加载用户偏好
            String mergedJson;// 合并后的用户偏好 JSON
            if (existing != null && existing.getPreferences() != null
                    && !existing.getPreferences().isBlank()) {
                mergedJson = mergeProfiles(existing.getPreferences(), extracted);// 合并用户偏好
            } else {
                mergedJson = extracted;// 无历史偏好时，直接使用新提取的信息填充
            }

            if (existing != null) {
                existing.setPreferences(mergedJson);// 更新用户偏好
                existing.setVersion(existing.getVersion() + 1);// 版本号 +1
                existing.setUpdatedAt(LocalDateTime.now());// 更新时间
                profileMapper.updateById(existing);// 更新数据库中的用户偏好
            } else {
                UserProfile profile = new UserProfile();// 创建新用户偏好记录
                profile.setUserId(userId);// 设置用户 ID
                profile.setPreferences(mergedJson);// 设置用户偏好
                profile.setVersion(1);// 版本号为 1
                profileMapper.insert(profile);// 插入数据库
            }

            profileCache.put(userId, mergedJson);// 缓存合并后的用户偏好
            log.debug("合并后的用户偏好: {}", mergedJson);
        } catch (Exception e) {
            log.warn("Failed to extract/save user profile for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * 获取用户画像的可用 JSON 字符串。
     * 先查内存缓存，未命中查 MySQL。
     *
     * @param userId 用户 ID
     * @return JSON 字符串，无画像时返回 null
     */
    public String getProfile(String userId) {
        String cached = profileCache.get(userId);// 从缓存中获取用户偏好
        if (cached != null) {
            return cached;
        }
        UserProfile profile = loadProfile(userId);// 从数据库中加载用户偏好
        if (profile != null && profile.getPreferences() != null
                && !profile.getPreferences().isBlank()) {
            profileCache.put(userId, profile.getPreferences());// 缓存用户偏好
            return profile.getPreferences();
        }
        return null;
    }

    /**
     * 构建可注入 AgentContext 的用户画像文本。
     * 将 JSON 转为自然语言，供 LLM 在生成回答时参考。
     *
     * @param userId 用户 ID
     * @return 如 "用户所在城市：北京，职业：程序员"，无画像时返回空字符串
     */
    public String buildUserContextPrompt(String userId) {
        String profileJson = getProfile(userId);// 从数据库中加载用户偏好 JSON
        if (profileJson == null || profileJson.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【用户画像】\n");

        Map<String, String> fields = parseSimpleJson(profileJson);// 解析 JSON 字符串为键值对
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value != null && !value.isBlank() && !"null".equals(value)) {
                String label = switch (key) {
                    case "city" -> "所在城市";
                    case "language" -> "偏好语言";
                    case "role" -> "职业";
                    case "interests" -> "兴趣爱好";
                    case "tech_stack" -> "技术栈";
                    default -> key;
                };
                sb.append("- ").append(label).append("：").append(value).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 删除指定用户的画像。
     */
    public void deleteProfile(String userId) {
        LambdaQueryWrapper<UserProfile> qw = new LambdaQueryWrapper<>();
        qw.eq(UserProfile::getUserId, userId);
        profileMapper.delete(qw);
        profileCache.remove(userId);
    }

    // ======================== 内部方法 ========================

    /**
     * 调用 LLM 从单条用户消息中提取结构化偏好信息。
     * LLM 返回纯 JSON。
     */
    private String extractPreferences(String content) {
        String prompt = String.format(EXTRACT_PROMPT, content);// 构建提取用户偏好的提示词
        return chatClient.chat(prompt);// 调用 LLM 提取用户偏好
    }

    /**
     * 调用 LLM 将新提取的信息合并到已有画像中。
     */
    private String mergeProfiles(String existingJson, String newJson) {
        String prompt = String.format(MERGE_PROMPT, existingJson, newJson);
        return chatClient.chat(prompt);
    }

    /**
     * 从 MySQL 加载用户画像。
     */
    private UserProfile loadProfile(String userId) {
        LambdaQueryWrapper<UserProfile> qw = new LambdaQueryWrapper<>();
        qw.eq(UserProfile::getUserId, userId);
        List<UserProfile> results = profileMapper.selectList(qw);
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 将简单的一层 JSON 对象解析为 key→value Map。
     * 支持字符串值和数组值（数组转为逗号分隔字符串）。
     */
    private Map<String, String> parseSimpleJson(String json) {
        Map<String, String> result = new ConcurrentHashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }

        String trimmed = json.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return result;
        }

        String body = trimmed.substring(start + 1, end);
        int depth = 0;
        int segmentStart = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '[') depth++;
            if (c == ']') depth--;
            if (c == ',' && depth == 0) {
                parseEntry(body.substring(segmentStart, i), result);
                segmentStart = i + 1;
            }
        }
        parseEntry(body.substring(segmentStart), result);// 解析最后一个键值对

        return result;
    }

    private void parseEntry(String entry, Map<String, String> result) {
        int colon = entry.indexOf(':');
        if (colon < 0) return;
        String key = entry.substring(0, colon).strip().replace("\"", "");
        String rawValue = entry.substring(colon + 1).strip();
        if (rawValue.startsWith("[")) {
            rawValue = rawValue.replace("[", "").replace("]", "")
                    .replace("\"", "").replace("，", ",");
        } else {
            rawValue = rawValue.replace("\"", "");
        }
        if (!key.isEmpty() && !rawValue.isEmpty()) {
            result.put(key, rawValue);
        }
    }

    // ======================== Memory 接口的空实现 ========================

    @Override
    public void add(Message message) {
    }

    @Override
    public void addAll(List<Message> messages) {
    }

    @Override
    public List<Message> getRecent(int count) {
        return List.of();
    }

    @Override
    public List<Message> getAll() {
        return List.of();
    }

    @Override
    public void compact(int maxTokens) {
    }

    @Override
    public void clear() {
        profileCache.clear();
    }
}

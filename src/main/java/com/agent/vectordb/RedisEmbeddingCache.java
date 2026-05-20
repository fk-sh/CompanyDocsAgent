package com.agent.vectordb;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 Redis 的 Embedding 缓存实现。
 * <p>
 * Key 格式：{@code embedding:cache:{SHA-256(text)}}，对原始文本做 SHA-256 摘要避免 key 过长和特殊字符。
 * Value 为 {@code float[1024]} 的 Jackson JSON 序列化结果。
 * TTL 由 {@link EmbeddingConfig#getCacheTtl()} 控制，默认 24h。
 * <p>
 * 仅在配置了 {@code spring.data.redis.host} 时激活；
 * 测试环境未配置 Redis 时，此 Bean 不会创建，由 {@code EmbeddingCacheTestConfig} 提供内存实现。
 * 由 {@link org.springframework.context.annotation.Primary @Primary} 确保生产环境优先使用。
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(prefix = "spring.data.redis", name = "host")
public class RedisEmbeddingCache implements EmbeddingCache {

    private static final String KEY_PREFIX = "embedding:cache:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisEmbeddingCache(StringRedisTemplate stringRedisTemplate,
                               ObjectMapper objectMapper,
                               EmbeddingConfig config) {
        this.redisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = config.getCacheTtl();
    }

    @Override
    public Optional<float[]> get(String text) {
        String key = toKey(text);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, float[].class));// 从 JSON 反序列化为 float[]
        } catch (Exception e) {
            log.warn("Failed to deserialize embedding from Redis key={}", key, e);
            redisTemplate.delete(key);
            return Optional.empty();
        }
    }

    @Override
    public void put(String text, float[] embedding) {
        try {
            String json = objectMapper.writeValueAsString(embedding);// 序列化为 JSON 字符串
            redisTemplate.opsForValue().set(toKey(text), json, ttl);
        } catch (Exception e) {
            log.warn("Failed to cache embedding to Redis", e);
        }
    }

    @Override
    public long size() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        return keys != null ? keys.size() : 0;
    }

    @Override
    public void clear() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Cleared {} embedding cache entries from Redis", keys.size());
        }
    }

    /**
     * 将文本转为 Redis key：{@code embedding:cache:{SHA-256(text)}}。
     */
    private static String toKey(String text) {//文本内容，不是用户提问
        return KEY_PREFIX + sha256(text);
    }

    /**
     * 计算输入字符串的 SHA-256 十六进制摘要。
     */
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

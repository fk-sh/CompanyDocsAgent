package com.agent.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class JwtService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    @Value("${auth.jwt.secret:CompanyDocsAgentChangeMeSecretKeyForDevOnly}")
    private String secret;

    @Value("${auth.jwt.ttl-seconds:86400}")
    private long ttlSeconds;

    // 生成JWT令牌
    // @param userId 用户ID
    // @param role   角色
    // @return JWT令牌
    // @throws IllegalStateException 如果生成令牌失败
       public String generate(String userId, String role) {
        try {
            Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);// 添加用户ID到负载
            payload.put("role", role);// 添加角色到负载
            payload.put("exp", Instant.now().getEpochSecond() + ttlSeconds);// 添加过期时间到负载

            String headerPart = encodeJson(header);// 编码JWT头
            String payloadPart = encodeJson(payload);// 编码JWT负载
            String signingInput = headerPart + "." + payloadPart;// 构建JWT签名输入
            return signingInput + "." + sign(signingInput);// 生成JWT令牌
        } catch (Exception e) {
            throw new IllegalStateException("Token 生成失败", e);
        }
    }

    // 从JWT令牌中解析用户ID
    // @param token JWT令牌
    // @return 用户ID
    // @throws UnauthorizedException 如果令牌无效
    public String parseUserId(String token) {
        Map<String, Object> payload = parsePayload(token);// 解析JWT负载
        // 从负载中获取用户ID
        Object userId = payload.get("userId");
        if (userId == null || userId.toString().isBlank()) {
            throw new UnauthorizedException("Token 无效");
        }
        return userId.toString();// 返回用户ID
    }

    // 解析JWT负载
    // @param token JWT令牌
    // @return JWT负载
    // @throws UnauthorizedException 如果令牌无效
    private Map<String, Object> parsePayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new UnauthorizedException("Token 格式错误");
            }
            String signingInput = parts[0] + "." + parts[1];
            String expected = sign(signingInput);
            if (!MessageDigestSafeEquals.equals(expected, parts[2])) {
                throw new UnauthorizedException("Token 签名无效");
            }
            Map<String, Object> payload = MAPPER.readValue(URL_DECODER.decode(parts[1]), new TypeReference<>() {});
            Object exp = payload.get("exp");
            long expSeconds = exp instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(exp));
            if (Instant.now().getEpochSecond() >= expSeconds) {
                throw new UnauthorizedException("登录已过期");
            }
            return payload;
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            throw new UnauthorizedException("Token 解析失败");
        }
    }

    // 编码JSON字符串为URL64编码
    // @param value JSON字符串
    // @return 编码后的URL64字符串
    // @throws Exception 如果编码失败
    // @return 编码后的URL64字符串
    // @throws Exception 如果编码失败
    private String encodeJson(Map<String, Object> value) throws Exception {
        return URL_ENCODER.encodeToString(MAPPER.writeValueAsBytes(value));
    }

    private String sign(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return URL_ENCODER.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class MessageDigestSafeEquals {
        static boolean equals(String a, String b) {
            return java.security.MessageDigest.isEqual(
                    a.getBytes(StandardCharsets.UTF_8),
                    b.getBytes(StandardCharsets.UTF_8)
            );
        }
    }
}

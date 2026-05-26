package com.smartwealth.common.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 *
 * 【SECURITY】密钥与过期时间不再硬编码，统一由 Spring 配置注入：
 *   - smartwealth.security.jwt-secret   （生产强制覆盖）
 *   - smartwealth.security.jwt-expire-ms
 *
 * 仍保留静态方法签名，方便所有 Controller/Filter 直接静态调用，
 * 实际密钥由 @PostConstruct 在 Spring 启动时一次性写入静态字段。
 */
@Slf4j
@Component
public class JwtUtils {

    private static volatile SecretKey SECRET_KEY;
    private static volatile long EXPIRE_TIME;

    @Value("${smartwealth.security.jwt-secret}")
    private String secret;

    @Value("${smartwealth.security.jwt-expire-ms:604800000}")
    private long expireMs;

    @PostConstruct
    public void init() {
        if (secret == null || secret.isBlank() || secret.startsWith("CHANGE_ME")) {
            // 启动期硬性校验：不允许生产带着默认密钥跑
            log.warn("⚠️ JWT 密钥使用了默认值，生产环境必须通过 SMARTWEALTH_JWT_SECRET 覆盖！");
        }
        SECRET_KEY = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        EXPIRE_TIME = expireMs;
        log.info("JwtUtils 初始化完成，token 过期时间 = {} ms", EXPIRE_TIME);
    }

    /**
     * 生成 Token
     */
    public static String createToken(Long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRE_TIME))
                .signWith(SECRET_KEY)
                .compact();
    }

    /**
     * 解析并验证 Token
     */
    public static Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(SECRET_KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.error("Token 已过期");
        } catch (MalformedJwtException e) {
            log.error("Token 格式错误");
        } catch (Exception e) {
            log.error("Token 解析失败: {}", e.getMessage());
        }
        return null;
    }
}
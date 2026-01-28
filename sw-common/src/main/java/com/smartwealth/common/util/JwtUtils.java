package com.smartwealth.common.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
public class JwtUtils {

    // 密钥必须至少 32 个字符
    private static final String SECRET_STR = "SmartWealth_Private_Key_2026_Secure_Safe";
    private static final SecretKey SECRET_KEY = Keys.hmacShaKeyFor(SECRET_STR.getBytes(StandardCharsets.UTF_8));
    private static final long EXPIRE_TIME = 7*24 * 60 * 60 * 1000L;

    /**
     * 生成 Token
     */
    public static String createToken(Long userId, String role) {
        return Jwts.builder()
                .subject(String.valueOf(userId)) // 建议将 userId 放入 sub
                .claim("role", role)             // 自定义属性
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRE_TIME))
                .signWith(SECRET_KEY)            // 现代写法自动识别算法
                .compact();
    }

    /**
     * 解析并验证 Token
     */
    public static Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(SECRET_KEY)      // 现代写法使用 verifyWith
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
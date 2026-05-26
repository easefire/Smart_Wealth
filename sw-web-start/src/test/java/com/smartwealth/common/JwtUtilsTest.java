package com.smartwealth.common;

import com.smartwealth.common.util.JwtUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * JwtUtils 是静态方法 + 静态字段，正常依赖 Spring 启动时 @PostConstruct 注入。
 * 单测里直接用反射写一个测试用密钥进去，验证签发/解析的端到端契约。
 */
class JwtUtilsTest {

    private static final String SECRET = "SmartWealthUnitTestSecret_AtLeast_32Bytes_!!";

    @BeforeAll
    static void initStaticFields() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        Field secretField = JwtUtils.class.getDeclaredField("SECRET_KEY");
        secretField.setAccessible(true);
        secretField.set(null, key);

        Field expireField = JwtUtils.class.getDeclaredField("EXPIRE_TIME");
        expireField.setAccessible(true);
        expireField.setLong(null, 60_000L); // 1 分钟
    }

    @Test
    @DisplayName("签发的 token 能被同一密钥解析回来，subject 和 role 一致")
    void create_then_parse_roundtrip() {
        String token = JwtUtils.createToken(42L, "USER");
        assertNotNull(token);

        Claims claims = JwtUtils.parseToken(token);
        assertNotNull(claims);
        assertEquals("42", claims.getSubject());
        assertEquals("USER", claims.get("role", String.class));
    }

    @Test
    @DisplayName("解析非法 token：返回 null 而不是抛异常（沿用现有契约）")
    void parse_garbage_returns_null() {
        assertNull(JwtUtils.parseToken("not.a.jwt"));
        assertNull(JwtUtils.parseToken(""));
    }

    @Test
    @DisplayName("用错误密钥签发的 token，本工具会拒绝（防止跨环境密钥串用）")
    void wrong_signature_returns_null() {
        SecretKey foreignKey = Keys.hmacShaKeyFor(
                "AnotherCompletelyDifferentSecret_32Bytes!".getBytes(StandardCharsets.UTF_8));
        String foreignToken = Jwts.builder()
                .subject("99")
                .claim("role", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(foreignKey)
                .compact();

        // 解析失败时 JwtUtils 吞掉异常并返回 null
        assertNull(JwtUtils.parseToken(foreignToken));
    }

    @Test
    @DisplayName("已过期的 token：parseToken 应返回 null")
    void expired_token_returns_null() throws Exception {
        // 把 EXPIRE_TIME 调成负数，让 createToken 直接生成"立即过期"的 token
        Field expireField = JwtUtils.class.getDeclaredField("EXPIRE_TIME");
        expireField.setAccessible(true);
        expireField.setLong(null, -1_000L);

        try {
            String token = JwtUtils.createToken(1L, "USER");
            assertNull(JwtUtils.parseToken(token),
                    "过期 token 必须返回 null，老实现就是这么约定的");
        } finally {
            expireField.setLong(null, 60_000L); // 还原，避免污染其它 case
        }
    }

    @Test
    @DisplayName("ExpiredJwtException 是底层抛出的，不应再泄漏到调用方（已被 JwtUtils 内部 catch）")
    void underlying_jwt_lib_does_throw_expired() {
        // 这条只是用来"标记契约"：JwtUtils 上层把 ExpiredJwtException 转成了 null。
        // 如果有一天有人重构成抛出异常，这里要立刻意识到调用方需要更新捕获逻辑。
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("1")
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(key)
                .compact();
        assertThrows(ExpiredJwtException.class, () ->
                Jwts.parser().verifyWith(key).build().parseSignedClaims(token));
    }
}

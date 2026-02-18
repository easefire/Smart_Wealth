package com.smartwealth.common.filter;

import com.smartwealth.common.context.UserContext;
import com.smartwealth.common.redis.constant.RedisKeyConstants;
import com.smartwealth.common.redis.service.RedisService;
import io.jsonwebtoken.Claims;
import com.smartwealth.common.util.JwtUtils;
import io.jsonwebtoken.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private RedisService redisService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException, java.io.IOException {
        // 从请求头中获取 JWT Token
        String token = request.getHeader("Authorization");
        // 去除 "Bearer " 前缀
        if (StringUtils.hasText(token) && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }
        // 验证 Token 有效性
        if (StringUtils.hasText(token)) {
            try {
                Claims claims = JwtUtils.parseToken(token);
                if (claims != null) {
                    Long userId = Long.valueOf(claims.getSubject());
                    String role = claims.get("role", String.class);
                    String redisKey;
                    if (role.equals("ADMIN")) {
                        redisKey = String.format(RedisKeyConstants.ADMIN_TOKEN, userId);
                    } else if (role.equals("USER")) {
                        redisKey = String.format(RedisKeyConstants.USER_TOKEN, userId);
                    } else
                        throw new IllegalArgumentException("未知的用户角色");
                    // 从 Redis 获取该用户当前合法的 Token
                    String cachedToken = (String) redisService.get(redisKey);
                    if (StringUtils.hasText(cachedToken) && cachedToken.equals(token)) {
                        Long ttl = redisService.getExpire(redisKey);
                        // 如果剩余有效期少于 15 分钟，则续期至 30 分钟
                        if (ttl != null && ttl < 15 * 60) {
                            redisService.expire(redisKey, 30, TimeUnit.MINUTES);
                        }
                        // 构建 Authentication 对象并存入 SecurityContext
                        String authorityRole = "ROLE_" + role;
                        List<SimpleGrantedAuthority> authorities =
                                Collections.singletonList(new SimpleGrantedAuthority(authorityRole));
                        // 将用户信息存入 UserContext
                        UserContext.set(userId, role);
                        // 构建 Authentication 对象
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(userId, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        log.warn("用户 {} 的 Token 已失效或已登出", userId);
                    }
                }
            } catch (Exception e) {
                log.error("JWT 认证失败: {}", e.getMessage());
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
    // 在 JwtAuthenticationFilter.java 中添加或重写此方法
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // 🔍 只要是下面这些路径，本过滤器直接“自杀”，彻底不拦截
        return path.contains("/doc.html") ||
                path.contains("/v3/api-docs") ||
                path.contains("/swagger-resources") ||
                path.contains("/webjars") ||
                path.contains("/favicon.ico") ||
                path.contains("/error");
    }
}

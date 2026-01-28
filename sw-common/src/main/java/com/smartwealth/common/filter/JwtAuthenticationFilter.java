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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Autowired
    private RedisService redisService; // 注入你刚封装好的 RedisService

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException, java.io.IOException {
        String token = request.getHeader("Authorization");

        if (StringUtils.hasText(token) && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (StringUtils.hasText(token)) {
            try {
                Claims claims = JwtUtils.parseToken(token);
                if (claims != null) {
                    Long userId = Long.valueOf(claims.getSubject());
                    String role = claims.get("role", String.class);
                    String redisKey=new String();

                    if(role.equals("ADMIN")) {
                        redisKey = String.format(RedisKeyConstants.ADMIN_TOKEN, userId);
                    }else if(role.equals("USER")) {
                        redisKey = String.format(RedisKeyConstants.USER_TOKEN, userId);
                    }else
                        throw new IllegalArgumentException("未知的用户角色");

                    // 从 Redis 获取该用户当前合法的 Token
                    String cachedToken = (String) redisService.get(redisKey);
                    // 判断：Redis 里没值（已登出）或者 Token 不匹配（被顶下线）
                    if (StringUtils.hasText(cachedToken) && cachedToken.equals(token)) {
                        Long ttl = redisService.getExpire(redisKey);
                        if (ttl != null && ttl < 15 * 60) { // 剩余不足 15 分钟
                            redisService.expire(redisKey, 30, TimeUnit.MINUTES);
                        }
                        // 只有 Redis 校验通过，才填充上下文
                        UserContext.set(userId, role);
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(userId, null, new ArrayList<>());
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    } else {
                        // 如果 Redis 校验失败，不设置 SecurityContext，请求后续会被安全模块拦截
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
}

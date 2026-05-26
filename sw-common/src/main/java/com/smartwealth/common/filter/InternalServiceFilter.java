package com.smartwealth.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 【BUGFIX-#10】
 *   旧 SecurityConfig 用 hasRole("INTERNAL_SERVICE") 锁 /sw/system/**，
 *   但全工程没有任何过滤器/服务给请求赋这个角色，
 *   结果 Python AI 服务等外部调用方一定会被 403 拦截。
 *
 *   本过滤器做的事很克制：
 *     1) 只在路径前缀 /sw/system 上生效；
 *     2) 检查来源 IP 是否在 smartwealth.security.allowed-ips 白名单内；
 *     3) 命中白名单 → 注入 ROLE_INTERNAL_SERVICE，让 SecurityConfig 放行；
 *     4) 否则什么都不做，由后续 authorizeHttpRequests 自然返回 403。
 *
 *   注意：不依赖任何 token，外网调用必须先被网关挡住。
 *         若未来要走 mTLS / API-Key，请扩展此 Filter，不要拆散这层职责。
 */
@Component
@Slf4j
public class InternalServiceFilter extends OncePerRequestFilter {

    private static final String INTERNAL_PATH_PREFIX = "/sw/system";
    private static final String INTERNAL_ROLE = "ROLE_INTERNAL_SERVICE";

    @Value("${smartwealth.security.allowed-ips:}")
    private String allowedIpsRaw;

    private volatile Set<String> allowedIps = Collections.emptySet();

    @Override
    protected void initFilterBean() {
        if (allowedIpsRaw == null || allowedIpsRaw.isBlank()) {
            log.warn("⚠️ smartwealth.security.allowed-ips 未配置，所有 /sw/system/** 请求都会被 403。");
            this.allowedIps = Collections.emptySet();
            return;
        }
        this.allowedIps = new HashSet<>(Arrays.asList(allowedIpsRaw.split("\\s*,\\s*")));
        log.info("InternalServiceFilter 已加载内部服务 IP 白名单：{}", allowedIps);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String clientIp = resolveClientIp(request);
        if (allowedIps.contains(clientIp)) {
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    "internal-service:" + clientIp,
                    null,
                    Collections.singletonList(new SimpleGrantedAuthority(INTERNAL_ROLE))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("内部接口放行：clientIp={} → 注入 {}", clientIp, INTERNAL_ROLE);
        } else {
            log.warn("⛔ 非白名单 IP 访问内部接口：clientIp={}, uri={}", clientIp, request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 仅在内部接口路径生效，其他请求不要污染 SecurityContext。
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    /**
     * 在反向代理（Nginx / ALB）后部署时，X-Forwarded-For 头携带真实 IP。
     * 顺位回退到 RemoteAddr，避免代理未配置时丢失客户端 IP。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 多级代理时取第一个 IP
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}

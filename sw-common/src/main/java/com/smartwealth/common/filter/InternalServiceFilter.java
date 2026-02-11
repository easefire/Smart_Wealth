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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
@Slf4j
public class InternalServiceFilter extends OncePerRequestFilter {

    @Value("${smartwealth.security.internal-key}")
    private String internalKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String secret = request.getHeader("X-Internal-Service-Key");

        if (StringUtils.hasText(secret) && internalKey.equals(secret)) {
            // 给 Python Agent 一个专门的系统角色
            SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_INTERNAL_SERVICE");

            // 构建一个系统级认证对象，Principal 设置为具体的服务名
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    "PYTHON_AGENT_SERVICE", null, Collections.singletonList(authority)
            );

            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("Internal service authenticated: Python Agent");
        }

        filterChain.doFilter(request, response);
    }
}
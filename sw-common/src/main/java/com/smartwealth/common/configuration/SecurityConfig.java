package com.smartwealth.common.configuration;

import com.smartwealth.common.filter.InternalServiceFilter;
import com.smartwealth.common.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 【BUGFIX-#10】内部接口角色赋予过滤器：
     * 仅当请求来自白名单 IP 且路径前缀为 /sw/system 时注入 ROLE_INTERNAL_SERVICE。
     */
    @Autowired
    private InternalServiceFilter internalServiceFilter;
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    // 主要的安全过滤链配置
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 1. 彻底放行文档和登录
                        .requestMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**", "/sw/user/auth/**").permitAll()
                        // 2. 内部接口权限锁定
                        // 确保 InternalServiceFilter 成功赋予了 ROLE_INTERNAL_SERVICE 角色
                        .requestMatchers("/sw/system/**").hasRole("INTERNAL_SERVICE")
                        .anyRequest().authenticated()
                )
                // 3. 异常处理：如果是权限问题，打印具体原因
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, authException) -> {
                            log.error("❌ 认证失败: {} | 路径: {}", authException.getMessage(), request.getRequestURI());
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            log.error("❌ 权限不足: {} | 路径: {}", accessDeniedException.getMessage(), request.getRequestURI());
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden");
                        })
                )
                // 4. 关键：
                //    ① InternalServiceFilter 必须排在 JwtAuthenticationFilter 之前 ——
                //       内部服务调用通常不会带 JWT，靠 IP 白名单赋角色即可放行；
                //    ② JwtAuthenticationFilter 处理普通用户/管理员的 JWT 鉴权。
                .addFilterBefore(internalServiceFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);


        return http.build();
    }
}
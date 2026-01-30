package com.smartwealth.common.configuration;

import com.smartwealth.common.filter.JwtAuthenticationFilter;
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

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
    // 主要的安全过滤链配置
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. 禁用 CSRF (前后端分离 JWT 模式通常不需要)
                .csrf(AbstractHttpConfigurer::disable)
                // 2. 设置 Session 策略为无状态 (STATELESS)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 3. 请求授权规则配置
                .authorizeHttpRequests(auth -> auth
                        // --- 放行 Knife4j / Swagger 文档资源 ---
                        // doc.html 是页面，webjars 是静态资源(js/css)，v3/api-docs 是后端生成的接口文档 JSON
                        .requestMatchers(
                                "/doc.html",
                                "/webjars/**",
                                "/v3/api-docs/**",
                                "/swagger-resources/**",
                                "/favicon.ico"
                        ).permitAll()
                        // --- 放行 认证相关接口 ---
                        // 注册和登录接口允许匿名访问
                        .requestMatchers(
                                "/sw/user/auth/register",
                                "/sw/user/auth/login",
                                "/sw/admin/user/register",
                                "/sw/admin/user/login"
                        ).permitAll()
                        // 其他所有请求都需要认证
                        .anyRequest().authenticated()
                )
                // 4. 添加 JWT 过滤器
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
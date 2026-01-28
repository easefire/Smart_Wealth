package com.smartwealth.common.configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private final String SECURITY_SCHEME_NAME = "Authorization";

    /**
     * 1. 全局元数据与安全配置
     */
    @Bean
    public OpenAPI smartWealthOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartWealth 理财系统接口文档")
                        .version("v1.0")
                        .description("基于 SpringBoot的模块化理财平台接口说明"))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    /**
     * 2. 分组配置 - 用户端
     */
    @Bean
    public GroupedOpenApi userApi() {
        return GroupedOpenApi.builder()
                .group("用户端服务")
                .pathsToMatch("/sw/user/**") // 匹配所有用户端路径
                .build();
    }

    /**
     * 3. 分组配置 - 管理端
     */
    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("管理端后台")
                .pathsToMatch("/sw/admin/**") // 匹配所有管理端路径
                .build();
    }

}
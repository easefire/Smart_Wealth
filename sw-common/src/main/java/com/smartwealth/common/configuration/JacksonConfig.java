package com.smartwealth.common.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class JacksonConfig {
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper om = new ObjectMapper();
        // 1. 注册时间模块，解决 LocalDate 报错
        om.registerModule(new JavaTimeModule());
        // 2. 禁用“将日期序列化为时间戳”，使其以字符串格式存入 Redis
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 3. 忽略未知属性，防止反序列化崩溃
        om.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return om;
    }
}
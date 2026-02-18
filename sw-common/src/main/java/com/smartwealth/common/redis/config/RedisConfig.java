package com.smartwealth.common.redis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory, ObjectMapper objectMapper) {
        // 创建 RedisTemplate
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        // 配置连接工厂
        template.setConnectionFactory(factory);
        // 使用 GenericJackson2JsonRedisSerializer 序列化值
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        // 设置键和值的序列化器
        template.setKeySerializer(new StringRedisSerializer());
        // 设置值序列化器为 JSON 序列化器
        template.setValueSerializer(jsonSerializer);
        // 设置 Hash 键和值的序列化器
        template.setHashKeySerializer(new StringRedisSerializer());
        // 设置 Hash 值序列化器为 JSON 序列化器
        template.setHashValueSerializer(jsonSerializer);
        // 初始化模板
        template.afterPropertiesSet();
        return template;
    }
}

package com.smartwealth.common.redis.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        // 这里根据你的 Redis 地址配置
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setPassword("923827"); // 如果有密码，设置密码
        return Redisson.create(config);
    }
}
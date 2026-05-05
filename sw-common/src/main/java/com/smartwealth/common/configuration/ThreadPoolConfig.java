package com.smartwealth.common.configuration;


import jodd.util.concurrent.ThreadFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
public class ThreadPoolConfig {

    @Bean("settlementThreadPool")
    public ThreadPoolExecutor settlementThreadPool() {
        int core = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
                core + 1,                   // 核心线程数
                core * 2,                   // 最大线程数
                60L,                        // 空闲存活时间
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(5000), // 有界队列防止内存溢出
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @Bean("warmupThreadPool")
    public ThreadPoolExecutor warmupThreadPool() {
        int core = Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
                core + 1,
                core * 2,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
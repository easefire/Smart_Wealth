package com.smartwealth.swwebstart;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

@MapperScan("com.smartwealth.*.mapper")
@ComponentScan(basePackages = "com.smartwealth")
@SpringBootApplication
// 【BUGFIX】之前未开启 @EnableAsync，全工程 @Async 注解（审计日志、缓存预热等）
//          全部退化为同步执行，拖慢主流程。
@EnableAsync
public class SwWebStartApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwWebStartApplication.class, args);
    }

}

package com.smartwealth.swwebstart;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.cloud.openfeign.EnableFeignClients;

@MapperScan("com.smartwealth.*.mapper")
@ComponentScan(basePackages = "com.smartwealth")
@SpringBootApplication
public class SwWebStartApplication {

    public static void main(String[] args) {
        SpringApplication.run(SwWebStartApplication.class, args);
    }

}

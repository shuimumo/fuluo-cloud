package com.shuimumu.fuluogame;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.shuimumu.fuluogame")
@MapperScan("com.shuimumu.fuluogame.mapper")
@EnableFeignClients("com.shuimumu.fuluogame.api")
@EnableScheduling
public class PostApplication {
    public static void main(String[] args) {
        SpringApplication.run(PostApplication.class, args);
    }
}

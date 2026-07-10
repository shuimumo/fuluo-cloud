package com.shuimumu.fuluogame.config;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.shuimumu.fuluogame.utils.RedisIdWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisIdGeneratorConfig {

    @Bean
    public IdentifierGenerator identifierGenerator(RedisIdWorker redisIdWorker) {
        return entity -> {
            String prefix = entity.getClass().getSimpleName().toLowerCase();
            return redisIdWorker.nextId(prefix);
        };
    }
}

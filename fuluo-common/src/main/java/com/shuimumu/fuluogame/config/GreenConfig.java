package com.shuimumu.fuluogame.config;

import com.aliyun.green20220302.Client;
import com.aliyun.teaopenapi.models.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "aliyun.green", name = "access-key-id")
public class GreenConfig {

    @Value("${aliyun.green.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.green.access-key-secret}")
    private String accessKeySecret;

    @Bean
    public Client greenClient() throws Exception {
        Config config = new Config();
        config.setAccessKeyId(accessKeyId);
        config.setAccessKeySecret(accessKeySecret);
        config.setRegionId("cn-shanghai");
        config.setEndpoint("green-cip.cn-shanghai.aliyuncs.com");
        config.setConnectTimeout(3000);
        config.setReadTimeout(6000);
        return new Client(config);
    }
}

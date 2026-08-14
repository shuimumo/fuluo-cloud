package com.shuimumu.fuluogame.config;

import com.shuimumu.fuluogame.utils.AliOssUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
// P0 修复：改为按显式 enabled 开关加载，避免密钥外部化后空串仍触发 Bean 创建。
@ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "true")
public class OssConfig {

    @Value("${aliyun.oss.endpoint}")
    private String endpoint;

    @Value("${aliyun.oss.access-key-id}")
    private String accessKeyId;

    @Value("${aliyun.oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    @Bean
    public AliOssUtil aliOssUtil() {
        return new AliOssUtil(endpoint, accessKeyId, accessKeySecret, bucketName);
    }
}
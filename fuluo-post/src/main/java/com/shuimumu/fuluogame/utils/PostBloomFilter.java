package com.shuimumu.fuluogame.utils;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PostBloomFilter {

    private static final String BLOOM_KEY = "bloom:post";
    private volatile boolean inited;
    private final RedissonClient redissonClient;

    public PostBloomFilter(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 确保布隆过滤器已初始化（add/contains 之前都必须先 tryInit）。
     */
    private void initIfNeeded() {
        if (!inited) {
            synchronized (this) {
                if (!inited) {
                    RBloomFilter<Long> bloom = redissonClient.getBloomFilter(BLOOM_KEY);
                    if (bloom.isExists()) {
                        inited = true;
                    } else {
                        bloom.tryInit(100000L, 0.01);
                        inited = true;
                        log.info("布隆过滤器初始化完成 key={}", BLOOM_KEY);
                    }
                }
            }
        }
    }

    public boolean mightContain(Long postId) {
        initIfNeeded();
        return redissonClient.<Long>getBloomFilter(BLOOM_KEY).contains(postId);
    }

    public void add(Long postId) {
        initIfNeeded();
        redissonClient.<Long>getBloomFilter(BLOOM_KEY).add(postId);
    }
}
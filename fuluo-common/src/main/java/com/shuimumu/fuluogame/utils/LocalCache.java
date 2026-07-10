package com.shuimumu.fuluogame.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class LocalCache {

    private static final String INVALIDATE_TOPIC = "cache:invalidate";
    private Cache<String, Object> cache;
    private final RedissonClient redissonClient;
    private int listenerId;

    public LocalCache(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @PostConstruct
    public void init() {
        cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .recordStats()
                .build();
        RTopic topic = redissonClient.getTopic(INVALIDATE_TOPIC);
        listenerId = topic.addListener(String.class, (channel, key) -> {
            cache.invalidate(key);
            log.debug("本地缓存失效 key={}", key);
        });
        log.info("Caffeine本地缓存初始化完成，已订阅失效频道");
    }

    @PreDestroy
    public void destroy() {
        redissonClient.getTopic(INVALIDATE_TOPIC).removeListener(listenerId);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) cache.getIfPresent(key);
    }

    public void put(String key, Object value) {
        cache.put(key, value);
    }

    public void invalidate(String key) {
        cache.invalidate(key);
    }

    public void broadcastInvalidate(String key) {
        cache.invalidate(key);
        redissonClient.getTopic(INVALIDATE_TOPIC).publish(key);
    }
}

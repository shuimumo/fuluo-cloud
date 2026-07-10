package com.shuimumu.fuluogame.utils;

import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 缓存工具 — Redisson 分布式锁防击穿 + 空值防穿透
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10, r -> {
        Thread t = new Thread(r, "cache-rebuild");
        t.setDaemon(true);
        return t;
    });

    public CacheClient(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
    }

    /**
     * 防穿透：DB 不存在时缓存空值，避免反复查询不存在的数据。
     */
    public <T> T queryWithPassThrough(String key, Class<T> clazz, Supplier<T> dbFallback,
                                       long ttl, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json != null) {
            if (json.isEmpty()) return null;
            return JSONUtil.toBean(json, clazz);
        }
        T data = dbFallback.get();
        if (data == null) {
            stringRedisTemplate.opsForValue().set(key, "", ttl, unit);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(data), ttl, unit);
        return data;
    }

    /**
     * 防击穿：Redisson 分布式锁，缓存过期时只让一个线程重建。
     * 适用于无泛型嵌套的简单类型（String, UserVO 等）。
     */
    public <T> T queryWithMutex(String lockKey, String dataKey, Class<T> clazz,
                                 Supplier<T> dbFallback, long ttl, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(dataKey);
        if (json != null) {
            if (json.isEmpty()) return null;
            return JSONUtil.toBean(json, clazz);
        }

        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 30, TimeUnit.SECONDS)) {
                log.warn("获取锁超时 lockKey={}，降级查 DB", lockKey);
                return dbFallback.get();
            }

            json = stringRedisTemplate.opsForValue().get(dataKey);
            if (json != null) {
                if (json.isEmpty()) return null;
                return JSONUtil.toBean(json, clazz);
            }

            T data = dbFallback.get();
            if (data == null) {
                stringRedisTemplate.opsForValue().set(dataKey, "", ttl, unit);
                return null;
            }
            stringRedisTemplate.opsForValue().set(dataKey, JSONUtil.toJsonStr(data), ttl, unit);
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("缓存重建被中断", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 防击穿 + TypeReference 版，正确反序列化泛型嵌套类型（如 PageVO&lt;PostVO&gt;）。
     */
    public <T> T queryWithMutex(String lockKey, String dataKey, TypeReference<T> typeRef,
                                 Supplier<T> dbFallback, long ttl, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(dataKey);
        if (json != null) {
            if (json.isEmpty()) return null;
            return JSONUtil.toBean(json, typeRef, false);
        }

        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(3, 30, TimeUnit.SECONDS)) {
                log.warn("获取锁超时 lockKey={}，降级查 DB", lockKey);
                return dbFallback.get();
            }

            json = stringRedisTemplate.opsForValue().get(dataKey);
            if (json != null) {
                if (json.isEmpty()) return null;
                return JSONUtil.toBean(json, typeRef, false);
            }

            T data = dbFallback.get();
            if (data == null) {
                stringRedisTemplate.opsForValue().set(dataKey, "", ttl, unit);
                return null;
            }
            stringRedisTemplate.opsForValue().set(dataKey, JSONUtil.toJsonStr(data), ttl, unit);
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("缓存重建被中断", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 逻辑过期：缓存永久保存，过期后返回旧数据 + 异步重建。
     */
    public <T> T queryWithLogicalExpire(String key, Class<T> clazz, Supplier<T> dbFallback,
                                         long ttl, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T data = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return data;
        }
        String lockKey = "lock:" + key;
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.tryLock()) {
            CACHE_REBUILD_POOL.submit(() -> {
                try {
                    T newData = dbFallback.get();
                    setWithLogicalExpire(key, newData, ttl, unit);
                } catch (Exception e) {
                    log.error("逻辑过期异步重建失败 key={}", key, e);
                } finally {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                }
            });
        }
        return data;
    }

    public <T> T queryWithLogicalExpire(String key, TypeReference<T> typeRef, Supplier<T> dbFallback,
                                         long ttl, TimeUnit unit) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T data = JSONUtil.toBean((JSONObject) redisData.getData(), typeRef, false);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return data;
        }
        String lockKey = "lock:" + key;
        RLock lock = redissonClient.getLock(lockKey);
        if (lock.tryLock()) {
            CACHE_REBUILD_POOL.submit(() -> {
                try {
                    T newData = dbFallback.get();
                    setWithLogicalExpire(key, newData, ttl, unit);
                } catch (Exception e) {
                    log.error("逻辑过期异步重建失败 key={}", key, e);
                } finally {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                }
            });
        }
        return data;
    }

    public void setWithLogicalExpire(String key, Object value, long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(ttl)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public void set(String key, String value, long ttl, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, ttl, unit);
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    @PreDestroy
    public void shutdown() {
        CACHE_REBUILD_POOL.shutdown();
        try {
            CACHE_REBUILD_POOL.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

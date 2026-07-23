package com.shuimumu.fuluogame.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 计数 Hash 的安全初始化：只补缺失 field，绝不覆盖已有值。
 *
 * <p>典型竞态（旧 putAll）：
 * <pre>
 *   T1 ensure: 读 DB likes=10，准备 putAll
 *   T2 like:   HINCRBY likes -> 11
 *   T1 putAll: likes 被写回 10  → 增量丢失
 * </pre>
 *
 * <p>本工具用 HSETNX 原子写入，已存在的 field（含并发累加后的值）保持不变。
 */
public final class RedisHashCounter {

    private static final DefaultRedisScript<Long> HSETNX_INIT = new DefaultRedisScript<>();

    static {
        HSETNX_INIT.setLocation(new ClassPathResource("lua/hash_hsetnx_init.lua"));
        HSETNX_INIT.setResultType(Long.class);
    }

    private RedisHashCounter() {
    }

    /**
     * 对 {@code key} 中尚不存在的 field 写入 seed；已存在 field 跳过。
     *
     * @return 新写入的 field 数量；脚本失败时可能为 null
     */
    public static Long initMissingFields(StringRedisTemplate redis, String key, Map<String, String> seedFields) {
        if (redis == null || key == null || key.isEmpty() || seedFields == null || seedFields.isEmpty()) {
            return 0L;
        }
        List<String> args = new ArrayList<>(seedFields.size() * 2);
        for (Map.Entry<String, String> e : seedFields.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) {
                continue;
            }
            args.add(e.getKey());
            args.add(e.getValue() != null ? e.getValue() : "0");
        }
        if (args.isEmpty()) {
            return 0L;
        }
        return redis.execute(HSETNX_INIT, List.of(key), (Object[]) args.toArray(new String[0]));
    }

    /** 帖子计数三件套：likes / comments / views */
    public static Long initPostCounts(StringRedisTemplate redis, String key,
                                      long likes, long comments, long views) {
        return initMissingFields(redis, key, Map.of(
                "likes", String.valueOf(Math.max(likes, 0L)),
                "comments", String.valueOf(Math.max(comments, 0L)),
                "views", String.valueOf(Math.max(views, 0L))
        ));
    }

    /** 评论计数：likes */
    public static Long initCommentCounts(StringRedisTemplate redis, String key, long likes) {
        return initMissingFields(redis, key, Map.of(
                "likes", String.valueOf(Math.max(likes, 0L))
        ));
    }

    /** 用户统计四件套：posts / following / followers / likes */
    public static Long initUserStats(StringRedisTemplate redis, String key,
                                     long posts, long following, long followers, long likes) {
        return initMissingFields(redis, key, Map.of(
                "posts", String.valueOf(Math.max(posts, 0L)),
                "following", String.valueOf(Math.max(following, 0L)),
                "followers", String.valueOf(Math.max(followers, 0L)),
                "likes", String.valueOf(Math.max(likes, 0L))
        ));
    }

    /**
     * 计数变更后刷新 TTL（滑动过期：有写入就续命，长期无写入则过期释放内存）。
     * 注意：只应在计数真正变化或从 DB 播种后调用；纯读取不要续期。
     */
    public static void refreshTtl(StringRedisTemplate redis, String key, long ttl, TimeUnit unit) {
        if (redis == null || key == null || key.isEmpty() || ttl <= 0 || unit == null) {
            return;
        }
        redis.expire(key, ttl, unit);
    }
}

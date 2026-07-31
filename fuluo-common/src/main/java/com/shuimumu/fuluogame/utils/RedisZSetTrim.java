package com.shuimumu.fuluogame.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * ZSET 窗口裁剪：只保留分数最高的 max 个成员（适用于 latest 时间序、hot 热度序）。
 */
public final class RedisZSetTrim {

    private static final DefaultRedisScript<Long> TRIM_MAX = new DefaultRedisScript<>();

    static {
        TRIM_MAX.setLocation(new ClassPathResource("lua/zset_trim_max.lua"));
        TRIM_MAX.setResultType(Long.class);
    }

    private RedisZSetTrim() {
    }

    /**
     * 若 ZCARD > max，删除分数最低的多余成员，保留 top max。
     */
    public static Long trimToMax(StringRedisTemplate redis, String key, long max) {
        if (redis == null || key == null || key.isEmpty() || max < 1) {
            return 0L;
        }
        return redis.execute(TRIM_MAX, List.of(key), String.valueOf(max));
    }
}

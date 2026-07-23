package com.shuimumu.fuluogame.utils;

import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.entity.UserStat;
import com.shuimumu.fuluogame.mapper.UserStatMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * user:stats 的安全加载与初始化。
 * <p>在任何对 user:stats 的 HINCRBY / Lua 累加之前调用 {@link #ensureUserStats(Long)}，
 * 用 HSETNX 从 DB 补齐缺失字段，避免空 key 从 0 起跳或 putAll 覆盖并发增量。
 */
@Component
public class UserStatsRedisHelper {

    private static final int FIELD_COUNT = 4; // posts, following, followers, likes

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserStatMapper userStatMapper;

    /**
     * 确保 user:stats:{userId} 四字段齐全。
     * 缺失时从 user_stat 表播种；无 DB 记录则播种 0，便于后续原子累加。
     */
    public void ensureUserStats(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        String key = UserRedisKeys.stats(userId);
        Long hlen = stringRedisTemplate.opsForHash().size(key);
        if (hlen != null && hlen >= FIELD_COUNT) {
            return;
        }

        UserStat stat = userStatMapper.selectById(userId);
        long posts = 0L;
        long following = 0L;
        long followers = 0L;
        long likes = 0L;
        if (stat != null) {
            posts = nz(stat.getPosts());
            following = nz(stat.getFollowing());
            followers = nz(stat.getFollowers());
            likes = nz(stat.getLikes());
        }

        RedisHashCounter.initUserStats(stringRedisTemplate, key, posts, following, followers, likes);
        // 补齐后设置 TTL；字段已齐的快路径不刷新，由写路径维护过期
        stringRedisTemplate.expire(key, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
    }

    /**
     * 读取完整统计：先 ensure，再 HGETALL。
     *
     * @return [posts, following, followers, likes]
     */
    public long[] getStats(Long userId) {
        if (userId == null || userId <= 0) {
            return new long[]{0L, 0L, 0L, 0L};
        }
        ensureUserStats(userId);
        var map = stringRedisTemplate.opsForHash().entries(UserRedisKeys.stats(userId));
        if (map == null || map.isEmpty()) {
            return new long[]{0L, 0L, 0L, 0L};
        }
        return new long[]{
                RedisUtils.toLong(map.get("posts")),
                RedisUtils.toLong(map.get("following")),
                RedisUtils.toLong(map.get("followers")),
                RedisUtils.toLong(map.get("likes"))
        };
    }

    /** 新用户注册时初始化四字段为 0（HSETNX，幂等）。 */
    public void initZeroStats(Long userId) {
        if (userId == null || userId <= 0) {
            return;
        }
        String key = UserRedisKeys.stats(userId);
        RedisHashCounter.initUserStats(stringRedisTemplate, key, 0L, 0L, 0L, 0L);
        stringRedisTemplate.expire(key, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
    }

    private static long nz(Long v) {
        return v != null ? Math.max(v, 0L) : 0L;
    }
}

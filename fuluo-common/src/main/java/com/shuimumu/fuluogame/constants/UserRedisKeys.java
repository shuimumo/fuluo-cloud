package com.shuimumu.fuluogame.constants;

public class UserRedisKeys {
    public static String stats(Long userId)     { return "user:stats:" + userId; }
    public static String following(Long userId) { return "user:following:" + userId; }
    public static String followers(Long userId) { return "user:followers:" + userId; }
    public static String liked(Long userId)     { return "user:liked:" + userId; }
    public static String inbox(Long userId)     { return "user:inbox:" + userId; }
    public static String outbox(Long userId)    { return "user:outbox:" + userId; }
    public static String bigV()                 { return "user:bigv"; }

    /**
     * 用户统计 dirty 分片数，与帖子 dirty 分片策略一致：写按 id 入片，同步每片独立锁。
     */
    public static final int STATS_DIRTY_SHARD_COUNT = 4;

    /** 按用户 id 选择 stats dirty 分片（写路径） */
    public static String statsDirty(long userId) {
        return statsDirtyShard(statsDirtyShardIndex(userId));
    }

    public static String statsDirtyShard(int shard) {
        return "user:stats:dirty:" + shard;
    }

    /** 旧版未分片 key，仅兼容历史残留 */
    public static String statsDirtyLegacy() {
        return "user:stats:dirty";
    }

    public static int statsDirtyShardIndex(long userId) {
        return (int) Math.floorMod(userId, STATS_DIRTY_SHARD_COUNT);
    }

    public static String activeUsers()          { return "active:users"; }
    public static String feedMerged(Long userId){ return "feed:merged:" + userId; }

    /** 用户统计 Hash TTL：7 天；统计变更时刷新，长期无变更则过期 */
    public static final long USER_STATS_TTL = 7;
    public static final long CACHE_USER_TTL = 5;
    public static final long CACHE_USER_PROFILE_TTL = 5;
    public static final long USER_CONTENT_CACHE_TTL = 30;

    // 混合推拉 feed 流参数
    public static final long OUTBOX_MAX = 200;
    public static final long ACTIVE_WINDOW_DAYS = 7;
    public static final long ACTIVE_WINDOW_SEC = 7 * 86400;
}

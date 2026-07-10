package com.shuimumu.fuluogame.constants;

public class UserRedisKeys {
    public static String stats(Long userId)     { return "user:stats:" + userId; }
    public static String following(Long userId) { return "user:following:" + userId; }
    public static String followers(Long userId) { return "user:followers:" + userId; }
    public static String liked(Long userId)     { return "user:liked:" + userId; }
    public static String inbox(Long userId)     { return "user:inbox:" + userId; }
    public static String outbox(Long userId)    { return "user:outbox:" + userId; }
    public static String bigV()                 { return "user:bigv"; }
    public static String statsDirty()           { return "user:stats:dirty"; }
    public static String activeUsers()          { return "active:users"; }
    public static String feedMerged(Long userId){ return "feed:merged:" + userId; }

    public static final long USER_STATS_TTL = 7;
    public static final long CACHE_USER_TTL = 5;
    public static final long CACHE_USER_PROFILE_TTL = 5;
    public static final long USER_CONTENT_CACHE_TTL = 30;

    // 混合推拉 feed 流参数
    public static final long OUTBOX_MAX = 200;
    public static final long ACTIVE_WINDOW_DAYS = 7;
    public static final long ACTIVE_WINDOW_SEC = 7 * 86400;
}

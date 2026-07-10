package com.shuimumu.fuluogame.constants;

public class SystemRedisKeys {
    public static String loginCode(String phone)  { return "login:code:" + phone; }
    public static String loginCodeInterval(String phone) { return "login:code:interval:" + phone; }
    public static String loginToken(String token) { return "login:token:" + token; }
    public static String cachePostContent(Long id) { return "cache:postContent:" + id; }
    public static String cacheUser(Long id)      { return "cache:user:" + id; }
    public static String cacheUserProfile(Long id) { return "cache:userProfile:" + id; }
    public static String cacheCommentContent(Long id) { return "cache:comment:" + id; }
    public static String icr(String type, String date) { return "icr:" + type + ":" + date; }
    public static String cacheBanners()       { return "cache:banners"; }
    public static String cacheAnnouncements() { return "cache:announcements"; }
    public static String cacheAiSummarise(Long postId) { return "cache:ai:summarise:" + postId; }
    public static final long AI_SUMMARISE_TTL = 30;

    public static final long LOGIN_CODE_TTL = 2;
    public static final long LOGIN_CODE_INTERVAL_TTL = 60;
    public static final long LOGIN_USER_TTL = 30;
}
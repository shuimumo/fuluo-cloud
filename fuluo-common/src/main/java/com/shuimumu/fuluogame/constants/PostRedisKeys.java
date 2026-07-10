package com.shuimumu.fuluogame.constants;

public class PostRedisKeys {
    public static String counts(Long postId)  { return "post:counts:" + postId; }
    public static String likes(Long postId)   { return "post:likes:" + postId; }
    public static String dirty()              { return "post:dirty"; }
    public static String latestZset()         { return "post:latest"; }
    public static String hotZset()            { return "post:hot"; }
    public static String userPostsZset(Long userId) { return "user:posts:" + userId; }
    public static String commentZset(Long postId)       { return "post:comments:" + postId; }
    public static String userCommentZset(Long userId)   { return "user:comments:" + userId; }
    public static String replyZset(Long rootId) { return "comment:reply:root:" + rootId; }
    public static String commentLikes(Long commentId) { return "comment:likes:" + commentId; }
    public static String commentCounts(Long commentId) { return "comment:counts:" + commentId; }
    public static String commentHotZset(Long postId) { return "post:comments:hot:" + postId; }
    public static final long COMMENT_CONTENT_CACHE_TTL = 4320; // 3天

    public static final long POST_COUNTS_TTL = 7;
    public static final long POST_CONTENT_CACHE_TTL = 4320;    // 3天

    public static String topicPostsLatestZset(Long topicId) { return "topic:posts:latest:" + topicId; }
    public static String topicPostsHotZset(Long topicId)   { return "topic:posts:hot:" + topicId; }
    public static String postTopicIds(Long postId)          { return "post:topicIds:" + postId; }

    public static String topic(Long topicId)  { return "topic:" + topicId; }
    public static String topicDirty()         { return "topic:dirty"; }
}

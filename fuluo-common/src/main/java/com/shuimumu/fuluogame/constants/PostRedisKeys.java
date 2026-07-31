package com.shuimumu.fuluogame.constants;

public class PostRedisKeys {
    public static String counts(Long postId)  { return "post:counts:" + postId; }
    public static String likes(Long postId)   { return "post:likes:" + postId; }

    /**
     * dirty 分片数。写入按 id 哈希入片，同步任务每片独立锁，多实例可并行消费不同分片。
     * <p>变更分片数会导致新旧 key 并存，需兼容消费或迁移，请谨慎修改。
     */
    public static final int DIRTY_SHARD_COUNT = 4;

    /** 按帖子 id 选择 dirty 分片 key（写路径使用） */
    public static String dirty(long postId) {
        return dirtyShard(dirtyShardIndex(postId));
    }

    /** 指定分片 dirty key（同步任务遍历使用） */
    public static String dirtyShard(int shard) {
        return "post:dirty:" + shard;
    }

    /** 旧版未分片 key，仅兼容历史残留数据 */
    public static String dirtyLegacy() {
        return "post:dirty";
    }

    public static String latestZset()         { return "post:latest"; }
    public static String hotZset()            { return "post:hot"; }
    public static String userPostsZset(Long userId) { return "user:posts:" + userId; }
    public static String commentZset(Long postId)       { return "post:comments:" + postId; }
    public static String userCommentZset(Long userId)   { return "user:comments:" + userId; }
    public static String replyZset(Long rootId) { return "comment:reply:root:" + rootId; }
    public static String commentLikes(Long commentId) { return "comment:likes:" + commentId; }
    public static String commentCounts(Long commentId) { return "comment:counts:" + commentId; }
    public static String commentHotZset(Long postId) { return "post:comments:hot:" + postId; }

    /** 评论内容缓存 TTL：4320 分钟 = 3 天 */
    public static final long COMMENT_CONTENT_CACHE_TTL = 4320;
    /**
     * 帖子计数 Hash（post:counts）TTL：7 天。
     * 策略：计数发生变化时刷新；长时间无变化则过期，下次 ensure 从 DB 回填。
     */
    public static final long POST_COUNTS_TTL = 7;
    /**
     * 评论计数 Hash（comment:counts）TTL：7 天。
     * 策略同 post:counts，仅在点赞等变更时刷新。
     */
    public static final long COMMENT_COUNTS_TTL = 7;
    /**
     * 话题统计 Hash（topic:{id}）TTL：7 天。
     * 策略：views/posts 变更时刷新。
     */
    public static final long TOPIC_STATS_TTL = 7;
    /** 帖子内容缓存 TTL：4320 分钟 = 3 天 */
    public static final long POST_CONTENT_CACHE_TTL = 4320;

    /**
     * 全局最新流 ZSET 窗口上限。窗口内走 Redis，超出后降级 MySQL。
     * 写路径 ZADD 后裁剪到该大小。
     */
    public static final long LATEST_ZSET_MAX = 10000;
    /** 话题最新流 ZSET 窗口上限 */
    public static final long TOPIC_LATEST_ZSET_MAX = 2000;

    public static String topicPostsLatestZset(Long topicId) { return "topic:posts:latest:" + topicId; }
    public static String topicPostsHotZset(Long topicId)   { return "topic:posts:hot:" + topicId; }
    public static String postTopicIds(Long postId)          { return "post:topicIds:" + postId; }

    public static String topic(Long topicId)  { return "topic:" + topicId; }

    /** 按话题 id 选择 topic dirty 分片（写路径） */
    public static String topicDirty(long topicId) {
        return topicDirtyShard(dirtyShardIndex(topicId));
    }

    public static String topicDirtyShard(int shard) {
        return "topic:dirty:" + shard;
    }

    /** 旧版未分片 topic dirty，仅兼容 */
    public static String topicDirtyLegacy() {
        return "topic:dirty";
    }

    /** 非负取模分片，避免 id 为负时异常 */
    public static int dirtyShardIndex(long id) {
        return (int) Math.floorMod(id, DIRTY_SHARD_COUNT);
    }
}

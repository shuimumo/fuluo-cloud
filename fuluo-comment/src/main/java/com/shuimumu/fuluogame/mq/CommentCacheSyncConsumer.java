package com.shuimumu.fuluogame.mq;

import cn.hutool.json.JSONObject;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.mapper.CommentMapper;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.utils.RedisHashCounter;
import com.shuimumu.fuluogame.utils.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 评论缓存重建消费者（由 CanalCdcConsumer 在评论审核 2→0 时扇出到
 * {@link RabbitMQConfig#QUEUE_CACHE_COMMENT}）。
 */
@Slf4j
@Component
public class CommentCacheSyncConsumer {

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    /**
     * 确保 post:counts:{postId} 的 likes/comments/views 齐全。
     * 缺失字段从 DB 读取后通过 HSETNX 补齐，避免 putAll 覆盖并发 HINCRBY 增量。
     * 任何 HINCRBY 之前调用，防止空 key 从 0 开始累加。
     */
    private void ensurePostCounts(Long postId) {
        String key = PostRedisKeys.counts(postId);
        Long hlen = stringRedisTemplate.opsForHash().size(key);
        if (hlen != null && hlen >= 3) return; // likes/comments/views 已齐全
        Post post = postMapper.selectById(postId);
        if (post == null) return;
        RedisHashCounter.initPostCounts(
                stringRedisTemplate,
                key,
                post.getLikes() != null ? post.getLikes() : 0,
                post.getComments() != null ? post.getComments() : 0,
                post.getViews() != null ? post.getViews() : 0);
    }

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = RabbitMQConfig.QUEUE_CACHE_COMMENT)
    public void handleCache(Map<String, Object> msg) {
        try {
            Long commentId = Long.valueOf(msg.get("commentId").toString());
            Comment comment = commentMapper.selectById(commentId);
            if (comment == null || comment.getStatus() != 0) return;

            long score = comment.getCreateTime().toEpochSecond(java.time.ZoneOffset.UTC);
            String commentIdStr = comment.getId().toString();

            // 1. ZADD 评论/回复 ZSET + 用户评论 ZSET
            if (comment.getRootId() == null || comment.getRootId() == 0) {
                stringRedisTemplate.opsForZSet().add(PostRedisKeys.commentZset(comment.getPostId()), commentIdStr, score);
            } else {
                stringRedisTemplate.opsForZSet().add(PostRedisKeys.replyZset(comment.getRootId()), commentIdStr, score);
            }
            stringRedisTemplate.opsForZSet().add(PostRedisKeys.userCommentZset(comment.getUserId()), commentIdStr, score);

            // 2. 写评论缓存
            JSONObject json = new JSONObject();
            json.set("id", comment.getId());
            json.set("postId", comment.getPostId());
            json.set("userId", comment.getUserId());
            json.set("parentId", comment.getParentId() != null ? comment.getParentId() : 0L);
            json.set("content", comment.getContent());
            json.set("likes", comment.getLikes() != null ? comment.getLikes() : 0);
            json.set("createTime", comment.getCreateTime());
            json.set("status", 0);
            Post post = postMapper.selectById(comment.getPostId());
            if (post != null) json.set("postTitle", post.getTitle());
            cacheClient.set(SystemRedisKeys.cacheCommentContent(commentId), json.toString(),
                    PostRedisKeys.COMMENT_CONTENT_CACHE_TTL, TimeUnit.MINUTES);

            // 3. 评论计数 + 热力值 + 脏标记
            ensurePostCounts(comment.getPostId());
            stringRedisTemplate.opsForHash().increment(PostRedisKeys.counts(comment.getPostId()), "comments", 1);
            String postIdStr = comment.getPostId().toString();
            stringRedisTemplate.opsForZSet().incrementScore(PostRedisKeys.hotZset(), postIdStr, 0.3);
            Set<String> topicIds = stringRedisTemplate.opsForSet().members(PostRedisKeys.postTopicIds(comment.getPostId()));
            if (topicIds != null && !topicIds.isEmpty()) {
                for (String tid : topicIds) {
                    stringRedisTemplate.opsForZSet().incrementScore(
                            PostRedisKeys.topicPostsHotZset(Long.parseLong(tid)), postIdStr, 0.3);
                }
            }
            stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(), comment.getPostId().toString());

            log.info("评论缓存同步完成 commentId={}", commentId);
        } catch (Exception e) {
            log.error("CommentCacheSyncConsumer 处理失败 msg={}", msg, e);
            throw new RuntimeException("评论缓存同步失败，触发MQ重试", e);
        }
    }
}

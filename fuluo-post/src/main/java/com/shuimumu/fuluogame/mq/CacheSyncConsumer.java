package com.shuimumu.fuluogame.mq;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.PostTopic;
import com.shuimumu.fuluogame.mapper.CommentMapper;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.mapper.PostTopicMapper;
import com.shuimumu.fuluogame.utils.RedisHashCounter;
import com.shuimumu.fuluogame.utils.RedisZSetTrim;
import com.shuimumu.fuluogame.utils.CacheClient;
import com.shuimumu.fuluogame.vo.PostContentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CacheSyncConsumer {

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private PostTopicMapper postTopicMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;


        /**
     * 确保 post:counts:{postId} 的 likes/comments/views 齐全。
     * 缺失字段从 DB 读取后通过 HSETNX 补齐，避免 putAll 覆盖并发 HINCRBY 增量。
     * 任何 HINCRBY 之前调用，防止空 key 从 0 开始累加。
     * <p>仅在发生播种（字段补齐）时设置 TTL；字段已齐全的快路径不续期，
     * 让长期无互动的帖子计数自然过期。真正的计数变更由写路径 refreshTtl。
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
        // 从 DB 播种后设置初始 TTL
        RedisHashCounter.refreshTtl(stringRedisTemplate, key,
                PostRedisKeys.POST_COUNTS_TTL, java.util.concurrent.TimeUnit.DAYS);
    }

    @SuppressWarnings("unchecked")
    @RabbitListener(queues = RabbitMQConfig.QUEUE_CACHE)
    public void handleCache(Map<String, Object> msg) {
        try {
            String type = (String) msg.get("type");
            if ("post".equals(type)) {
                handlePost(msg);
            } else if ("comment".equals(type)) {
                handleComment(msg);
            }
        } catch (Exception e) {
            log.error("CacheSyncConsumer 处理失败 msg={}", msg, e);
            throw new RuntimeException("缓存同步失败，触发MQ重试", e);
        }
    }

    private void handlePost(Map<String, Object> msg) {
        Long postId = Long.valueOf(msg.get("postId").toString());
        Post post = postMapper.selectById(postId);
        if (post == null) return;

        long createEpoch = post.getCreateTime().toEpochSecond(java.time.ZoneOffset.UTC);
        String postIdStr = post.getId().toString();

        // 1. ZADD latest
        stringRedisTemplate.opsForZSet().add(PostRedisKeys.latestZset(), postIdStr, createEpoch);
        // 最新流只保留窗口，超出部分由列表接口降级 MySQL
        RedisZSetTrim.trimToMax(stringRedisTemplate, PostRedisKeys.latestZset(),
                PostRedisKeys.LATEST_ZSET_MAX);

        // 2. 写单帖缓存
        PostContentVO vo = new PostContentVO();
        vo.setId(post.getId());
        vo.setUserId(post.getUserId());
        vo.setTitle(post.getTitle());
        vo.setContent(post.getContent());
        vo.setCreateTime(post.getCreateTime());
        vo.setStatus(0);
        try {
            vo.setImages(post.getImages() != null && !post.getImages().isEmpty()
                    ? JSONUtil.toList(post.getImages(), String.class) : new java.util.ArrayList<>());
        } catch (Exception e) {
            vo.setImages(new java.util.ArrayList<>());
        }
        if (post.getTopicNames() != null && !post.getTopicNames().isEmpty()) {
            try {
                vo.setTopics(JSONUtil.toList(post.getTopicNames(), String.class));
            } catch (Exception e) {
                vo.setTopics(new java.util.ArrayList<>());
            }
        } else {
            vo.setTopics(new java.util.ArrayList<>());
        }
        cacheClient.set(SystemRedisKeys.cachePostContent(postId), JSONUtil.toJsonStr(vo),
                PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);

        // 3. 话题 ZSET
        List<PostTopic> pts = postTopicMapper.selectList(
                new LambdaQueryWrapper<PostTopic>().eq(PostTopic::getPostId, postId));
        for (PostTopic pt : pts) {
            stringRedisTemplate.opsForZSet().add(PostRedisKeys.topicPostsLatestZset(pt.getTopicId()), postIdStr, createEpoch);
            RedisZSetTrim.trimToMax(stringRedisTemplate, PostRedisKeys.topicPostsLatestZset(pt.getTopicId()),
                    PostRedisKeys.TOPIC_LATEST_ZSET_MAX);
            stringRedisTemplate.opsForSet().add(PostRedisKeys.postTopicIds(postId), pt.getTopicId().toString());
        }

        log.info("帖子缓存同步完成 postId={}", postId);
    }

    private void handleComment(Map<String, Object> msg) {
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

        // 3. 评论计数 + 热力值 + 脏标记（先确保计数 key 完整再累加）
        ensurePostCounts(comment.getPostId());
        stringRedisTemplate.opsForHash().increment(PostRedisKeys.counts(comment.getPostId()), "comments", 1);
            // 评论数变更：刷新 post:counts TTL
            RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.counts(comment.getPostId()),
                    PostRedisKeys.POST_COUNTS_TTL, java.util.concurrent.TimeUnit.DAYS);
        String postIdStr = comment.getPostId().toString();
        stringRedisTemplate.opsForZSet().incrementScore(PostRedisKeys.hotZset(), postIdStr, 0.3);
        Set<String> topicIds = stringRedisTemplate.opsForSet().members(PostRedisKeys.postTopicIds(comment.getPostId()));
        if (topicIds != null && !topicIds.isEmpty()) {
            for (String tid : topicIds) {
                stringRedisTemplate.opsForZSet().incrementScore(
                        PostRedisKeys.topicPostsHotZset(Long.parseLong(tid)), postIdStr, 0.3);
            }
        }
        stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(comment.getPostId()), comment.getPostId().toString());

        log.info("评论缓存同步完成 commentId={}", commentId);
    }
}

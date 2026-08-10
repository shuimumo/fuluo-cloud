package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.dto.PostModerationMsg;
import com.shuimumu.fuluogame.dto.PostPersistMsg;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.PostTopic;
import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.mapper.PostTopicMapper;
import com.shuimumu.fuluogame.mapper.TopicMapper;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 帖子落库消费者（批量）。
 * 使用独立批量监听容器（postBatchListenerContainerFactory），一次接收一批消息，
 * 新帖通过 insertBatch（ON DUPLICATE KEY 幂等）批量写库，提升写库吞吐。
 */
@Slf4j
@Component
public class PostPersistenceConsumer {

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostTopicMapper postTopicMapper;

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PERSISTENCE_POST,
            containerFactory = "postBatchListenerContainerFactory")
    public void handlePostPersist(List<PostPersistMsg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            Map<Long, Post> newPostsById = new HashMap<>();
            Map<Long, PostPersistMsg> newMsgsById = new HashMap<>();
            List<PostPersistMsg> updates = new ArrayList<>();
            for (PostPersistMsg msg : messages) {
                if (Boolean.TRUE.equals(msg.getIsNew())) {
                    Post post = buildPost(msg);
                    newPostsById.put(msg.getPostId(), post);
                    newMsgsById.put(msg.getPostId(), msg);
                } else {
                    updates.add(msg);
                }
            }

            // 1) 新帖批量落库
            if (!newPostsById.isEmpty()) {
                List<Post> newPosts = new ArrayList<>(newPostsById.values());
                fillTopicNames(newPosts, newMsgsById);
                postMapper.insertBatch(newPosts);
                for (Post post : newPosts) {
                    long zsetScore = post.getCreateTime().toEpochSecond(ZoneOffset.UTC);
                    stringRedisTemplate.opsForZSet().add(PostRedisKeys.userPostsZset(post.getUserId()),
                            post.getId().toString(), zsetScore);
                }
                for (PostPersistMsg msg : newMsgsById.values()) {
                    insertPostTopics(msg);
                }
            }

            // 2) 编辑更新逐个处理
            for (PostPersistMsg msg : updates) {
                handleUpdate(msg);
            }

            // 3) 逐条投递审核消息
            for (PostPersistMsg msg : messages) {
                PostModerationMsg modMsg = new PostModerationMsg(
                        msg.getPostId(), msg.getUserId(), msg.getIsNew(), msg.getTopicIds());
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE,
                        RabbitMQConfig.KEY_MODERATION, modMsg);
            }
            log.info("帖子批量落库完成 batchSize={}", messages.size());
        } catch (Exception e) {
            log.error("帖子批量落库失败 batchSize={}", messages.size(), e);
            throw new RuntimeException("帖子落库失败，触发MQ重试", e);
        }
    }

    private Post buildPost(PostPersistMsg msg) {
        Post post = new Post();
        post.setId(msg.getPostId());
        post.setUserId(msg.getUserId());
        post.setTitle(msg.getTitle());
        post.setContent(msg.getContent());
        post.setImages(msg.getImages());
        post.setLikes(0); post.setComments(0); post.setViews(0); post.setShares(0);
        post.setCreateTime(LocalDateTime.now());
        post.setUpdateTime(LocalDateTime.now());
        post.setStatus(2);
        return post;
    }

    /** 批量查询话题名并写入快照，避免逐条查 topic 表 */
    private void fillTopicNames(List<Post> posts, Map<Long, PostPersistMsg> msgs) {
        Set<Long> allTids = new HashSet<>();
        for (Post post : posts) {
            PostPersistMsg msg = msgs.get(post.getId());
            if (msg == null || msg.getTopicIds() == null || msg.getTopicIds().isEmpty()) {
                continue;
            }
            for (String t : msg.getTopicIds().split(",")) {
                allTids.add(Long.valueOf(t.trim()));
            }
        }
        if (allTids.isEmpty()) {
            return;
        }
        Map<Long, String> nameById = new HashMap<>();
        List<Topic> topics = topicMapper.selectByIds(new ArrayList<>(allTids));
        if (topics != null) {
            for (Topic t : topics) {
                if (t != null) nameById.put(t.getId(), t.getName());
            }
        }
        for (Post post : posts) {
            PostPersistMsg msg = msgs.get(post.getId());
            if (msg == null || msg.getTopicIds() == null || msg.getTopicIds().isEmpty()) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (String t : msg.getTopicIds().split(",")) {
                String name = nameById.get(Long.valueOf(t.trim()));
                if (name != null) names.add(name);
            }
            post.setTopicNames(JSONUtil.toJsonStr(names));
        }
    }

    private void insertPostTopics(PostPersistMsg msg) {
        if (msg.getTopicIds() == null || msg.getTopicIds().isEmpty()) {
            return;
        }
        for (String tid : msg.getTopicIds().split(",")) {
            PostTopic pt = new PostTopic();
            pt.setPostId(msg.getPostId());
            pt.setTopicId(Long.valueOf(tid.trim()));
            postTopicMapper.insert(pt);
        }
    }

    private void handleUpdate(PostPersistMsg msg) {
        Post post = postMapper.selectById(msg.getPostId());
        if (post == null || post.getStatus() == 1) {
            log.warn("编辑落库失败：帖子不存在或已删除 postId={}", msg.getPostId());
            return;
        }
        if (!post.getUserId().equals(msg.getUserId())) {
            log.warn("编辑落库失败：用户不匹配 postId={}", msg.getPostId());
            return;
        }
        if (msg.getTitle() != null) post.setTitle(msg.getTitle());
        if (msg.getContent() != null) post.setContent(msg.getContent());
        if (msg.getImages() != null) post.setImages(msg.getImages());
        post.setStatus(2);
        postMapper.updateById(post);
        // 从公开 ZSET 移除（审核通过后加回）
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.latestZset(), msg.getPostId().toString());
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.hotZset(), msg.getPostId().toString());
        // 缓存移除（审核通过后重建）
        stringRedisTemplate.delete(SystemRedisKeys.cachePostContent(msg.getPostId()));
    }
}
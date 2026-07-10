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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class PostPersistenceConsumer {

    @Autowired
    private PostMapper postMapper;

    @Autowired private
    PostTopicMapper postTopicMapper;

    @Autowired
    private TopicMapper topicMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PERSISTENCE_POST)
    public void handlePostPersist(PostPersistMsg msg) {
        try {
            if (Boolean.TRUE.equals(msg.getIsNew())) {
                // 新帖落库
                Post post = new Post();
                post.setId(msg.getPostId());
                post.setUserId(msg.getUserId());
                post.setTitle(msg.getTitle());
                post.setContent(msg.getContent());
                post.setImages(msg.getImages());
                post.setLikes(0); post.setComments(0); post.setViews(0); post.setShares(0);
                post.setCreateTime(java.time.LocalDateTime.now());
                post.setUpdateTime(java.time.LocalDateTime.now());
                post.setStatus(2);
                // 存话题名快照，避免每次展示都查post_topic+topic表
                if (msg.getTopicIds() != null && !msg.getTopicIds().isEmpty()) {
                    String[] tids = msg.getTopicIds().split(",");
                    List<Long> tidList = new ArrayList<>();
                    for (String tid : tids) tidList.add(Long.valueOf(tid.trim()));
                    List<Topic> topics = topicMapper.selectByIds(tidList);
                    List<String> names = new ArrayList<>();
                    for (Topic t : topics) if (t != null) names.add(t.getName());
                    post.setTopicNames(JSONUtil.toJsonStr(names));
                }
                try {
                    postMapper.insert(post);
                } catch (DuplicateKeyException e) {
                    post = postMapper.selectById(msg.getPostId());
                    if (post == null) throw new RuntimeException("主键重复，但是帖子不存在？", e);
                    log.info("帖子已存在，跳过落库 postId={}", msg.getPostId());
                    if (post.getStatus() != 2) return;
                }
                // 新帖加入用户帖子列表
                long zsetScore = post.getCreateTime().toEpochSecond(java.time.ZoneOffset.UTC);
                stringRedisTemplate.opsForZSet().add(PostRedisKeys.userPostsZset(post.getUserId()),
                        post.getId().toString(), zsetScore);
                // 新帖创建话题关联
                if (msg.getTopicIds() != null && !msg.getTopicIds().isEmpty()) {
                    for (String tid : msg.getTopicIds().split(",")) {
                        PostTopic pt = new PostTopic();
                        pt.setPostId(post.getId());
                        pt.setTopicId(Long.valueOf(tid.trim()));
                        postTopicMapper.insert(pt);
                    }
                }
            } else {
                // 编辑帖更新
                Post post = postMapper.selectById(msg.getPostId());
                if (post == null || post.getStatus() == 1) {
                    log.warn("编辑落库失败：帖子不存在或已删除 postId={}", msg.getPostId());
                    return;
                }
                if (!post.getUserId().equals(msg.getUserId())) {
                    log.warn("编辑落库失败：用户不匹配 postId={} userId={}", msg.getPostId(), msg.getUserId());
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

            // 发送审核消息，只传postId+isNew+topicIds，title/content审核时从DB查
            PostModerationMsg modMsg = new PostModerationMsg(
                    msg.getPostId(), msg.getUserId(), msg.getIsNew(), msg.getTopicIds());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.KEY_MODERATION, modMsg);
            log.info("帖子落库完成 postId={} isNew={}", msg.getPostId(), msg.getIsNew());
        } catch (Exception e) {
            log.error("帖子落库失败 msg={}", msg, e);
            throw new RuntimeException("帖子落库失败，触发MQ重试", e);
        }
    }
}

package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.dto.PostModerationMsg;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.utils.ContentModerationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PostModerationConsumer {

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private ContentModerationUtil contentModerationUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_MODERATION)
    public void handlePost(PostModerationMsg msg) {
        try {
            Post post = postMapper.selectById(msg.getPostId());
            if (post == null || post.getStatus() != 2) return;

            ContentModerationUtil.ModerationResult result =
                    contentModerationUtil.moderateComment(post.getTitle() + " " + post.getContent());

            ContentModerationUtil.Action action = result.getAction();
            if (action == ContentModerationUtil.Action.RETRY) {
                throw new RuntimeException("审核接口异常，触发MQ重试");
            }
            if (action == ContentModerationUtil.Action.PASS) {
                post.setStatus(0); post.setModerationDesc(null); post.setModerationWords(null);
                postMapper.updateById(post);
                // cache/es.index/inbox-outbox 由 status 2->0 的 binlog 事件驱动。
                // 这里只保留 binlog 无法推导的“新帖一次性”语义。
                if (Boolean.TRUE.equals(msg.getIsNew())) {
                    incrAuthorStats(post.getUserId());
                    if (msg.getTopicIds() != null && !msg.getTopicIds().isEmpty()) {
                        for (String tid : msg.getTopicIds().split(",")) {
                            Long topicId = Long.valueOf(tid.trim());
                            stringRedisTemplate.opsForHash().increment(PostRedisKeys.topic(topicId), "posts", 1);
                            stringRedisTemplate.opsForSet().add(PostRedisKeys.topicDirty(), topicId.toString());
                        }
                    }
                }
            } else {
                if (action == ContentModerationUtil.Action.MANUAL_REVIEW) {
                    post.setStatus(2);
                    log.warn("帖子待人工审核 postId={} desc={}", post.getId(), result.getDescription());
                } else {
                    post.setStatus(3);
                    log.warn("帖子审核拒绝 postId={} label={} desc={}", post.getId(),
                            result.getLabel(), result.getDescription());
                }
                post.setModerationDesc(result.getDescription());
                post.setModerationWords(result.getRiskWords());
                postMapper.updateById(post);
            }
            log.info("帖子审核完成 postId={} action={}", post.getId(), action);
        } catch (Exception e) {
            log.error("帖子审核失败 msg={}", msg, e);
            throw new RuntimeException("审核处理失败，触发MQ重试", e);
        }
    }

    private void incrAuthorStats(Long userId) {
        String key = UserRedisKeys.stats(userId);
        stringRedisTemplate.opsForHash().increment(key, "posts", 1);
        stringRedisTemplate.expire(key, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(), userId.toString());
    }
}

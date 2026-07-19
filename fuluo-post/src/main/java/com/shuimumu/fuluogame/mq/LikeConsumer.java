package com.shuimumu.fuluogame.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.dto.SyncMessage;
import com.shuimumu.fuluogame.dto.NotificationEvent;
import com.shuimumu.fuluogame.entity.Like;
import com.shuimumu.fuluogame.mapper.LikeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
public class LikeConsumer {

    private static final double LIKE_SCORE = 0.7;

    @Autowired
    private LikeMapper likeMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_LIKE)
    public void handleLike(SyncMessage msg) {
        log.info("收到点赞消息：{}", msg);
        try {
            handleLikeDb(msg);
            handleHotScore(msg);
            if ("like".equals(msg.getType())) {
                String eventId = msg.getEventId() != null
                        ? "post-like:" + msg.getEventId()
                        : "post-like:" + msg.getUserId() + ":" + msg.getTargetId();
                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                        RabbitMQConfig.NOTIFICATION_KEY,
                        NotificationEvent.interaction(eventId, NotificationEvent.POST_LIKE,
                                msg.getUserId(), msg.getTargetId(), msg.getTargetId()));
            }
            log.info("点赞消费完成 type={} userId={} targetId={}",
                    msg.getType(), msg.getUserId(), msg.getTargetId());
        } catch (Exception e) {
            log.error("点赞消费异常 msg={}", msg, e);
            throw e;
        }
    }

    private void handleLikeDb(SyncMessage msg) {
        Long userId = msg.getUserId();
        Long targetId = msg.getTargetId();
        String type = msg.getType();

        Like like = getLikeRecord(userId, targetId);

        if ("like".equals(type)) {
            doLike(like, userId, targetId);
        } else if ("unlike".equals(type)) {
            doUnlike(like);
        }
    }

    private void doLike(Like like, Long userId, Long targetId) {
        if (like != null) {
            if (like.getStatus() != 1) {
                like.setStatus(1);
                likeMapper.updateById(like);
            }
            return;
        }

        Like newLike = buildLike(userId, targetId, 1);
        try {
            likeMapper.insert(newLike);
        } catch (DuplicateKeyException e) {
            log.warn("点赞重复插入，转为更新 userId={} targetId={}", userId, targetId);
            Like exist = getLikeRecord(userId, targetId);
            if (exist != null && exist.getStatus() != 1) {
                exist.setStatus(1);
                likeMapper.updateById(exist);
            }
        }
    }

    private void doUnlike(Like like) {
        if (like != null && like.getStatus() == 1) {
            like.setStatus(0);
            likeMapper.updateById(like);
        }
    }

    private Like buildLike(Long userId, Long targetId, Integer status) {
        Like like = new Like();
        like.setUserId(userId);
        like.setTargetType(1);
        like.setTargetId(targetId);
        like.setStatus(status);
        return like;
    }

    private Like getLikeRecord(Long userId, Long targetId) {
        return likeMapper.selectOne(new LambdaQueryWrapper<Like>()
                .eq(Like::getUserId, userId)
                .eq(Like::getTargetType, 1)
                .eq(Like::getTargetId, targetId));
    }

    private void handleHotScore(SyncMessage msg) {
        Long targetId = msg.getTargetId();
        double delta = "like".equals(msg.getType()) ? LIKE_SCORE : -LIKE_SCORE;
        String postId = targetId.toString();

        stringRedisTemplate.opsForZSet().incrementScore(PostRedisKeys.hotZset(), postId, delta);

        Set<String> topicIds = stringRedisTemplate.opsForSet().members(PostRedisKeys.postTopicIds(targetId));
        if (topicIds == null || topicIds.isEmpty()) {
            return;
        }
        for (String topicIdStr : topicIds) {
            Long topicId = Long.parseLong(topicIdStr);
            stringRedisTemplate.opsForZSet().incrementScore(
                    PostRedisKeys.topicPostsHotZset(topicId), postId, delta);
        }
    }
}

package com.shuimumu.fuluogame.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.dto.SyncMessage;
import com.shuimumu.fuluogame.dto.NotificationEvent;
import com.shuimumu.fuluogame.entity.Like;
import com.shuimumu.fuluogame.mapper.LikeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CommentLikeConsumer {

    @Autowired
    private LikeMapper likeMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_COMMENT_LIKE)
    public void handleLike(SyncMessage msg) {
        log.info("收到评论点赞消息：{}", msg);
        try {
            handleLikeDb(msg);
            if ("like".equals(msg.getType())) {
                String eventId = msg.getEventId() != null
                        ? "comment-like:" + msg.getEventId()
                        : "comment-like:" + msg.getUserId() + ":" + msg.getTargetId();
                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                        RabbitMQConfig.NOTIFICATION_KEY,
                        NotificationEvent.interaction(eventId, NotificationEvent.COMMENT_LIKE,
                                msg.getUserId(), msg.getTargetId(), null));
            }
            log.info("评论点赞消费完成 type={} userId={} commentId={}",
                    msg.getType(), msg.getUserId(), msg.getTargetId());
        } catch (Exception e) {
            log.error("评论点赞消费异常 msg={}", msg, e);
            throw e;
        }
    }

    private void handleLikeDb(SyncMessage msg) {
        Long userId = msg.getUserId();
        Long commentId = msg.getTargetId();

        Like like = getLikeRecord(userId, commentId);

        if ("like".equals(msg.getType())) {
            doLike(like, userId, commentId);
        } else if ("unlike".equals(msg.getType())) {
            doUnlike(like);
        }
    }

    private void doLike(Like like, Long userId, Long commentId) {
        if (like != null) {
            if (like.getStatus() != 1) {
                like.setStatus(1);
                likeMapper.updateById(like);
            }
            return;
        }

        Like newLike = buildLike(userId, commentId, 1);
        try {
            likeMapper.insert(newLike);
        } catch (DuplicateKeyException e) {
            log.warn("评论点赞重复插入，转为更新 userId={} commentId={}", userId, commentId);
            Like exist = getLikeRecord(userId, commentId);
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

    private Like buildLike(Long userId, Long commentId, Integer status) {
        Like like = new Like();
        like.setUserId(userId);
        like.setTargetType(2);
        like.setTargetId(commentId);
        like.setStatus(status);
        return like;
    }

    private Like getLikeRecord(Long userId, Long commentId) {
        return likeMapper.selectOne(new LambdaQueryWrapper<Like>()
                .eq(Like::getUserId, userId)
                .eq(Like::getTargetType, 2)
                .eq(Like::getTargetId, commentId));
    }
}

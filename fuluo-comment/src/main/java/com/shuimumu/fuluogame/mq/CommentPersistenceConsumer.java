package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.dto.CommentModerationMsg;
import com.shuimumu.fuluogame.dto.CommentPersistMsg;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.mapper.CommentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CommentPersistenceConsumer {

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PERSISTENCE_COMMENT)
    public void handleCommentPersist(CommentPersistMsg msg) {
        try {
            Comment comment = new Comment();
            comment.setId(msg.getCommentId());
            comment.setPostId(msg.getPostId());
            comment.setUserId(msg.getUserId());
            comment.setContent(msg.getContent());
            comment.setParentId(msg.getParentId() != null ? msg.getParentId() : 0L);
            comment.setRootId(msg.getRootId() != null ? msg.getRootId() : 0L);
            comment.setCreateTime(java.time.LocalDateTime.now());
            comment.setLikes(0);
            comment.setStatus(2);
            try {
                commentMapper.insert(comment);
            } catch (DuplicateKeyException e) {
                comment = commentMapper.selectById(msg.getCommentId());
                if (comment == null) throw new RuntimeException("DuplicateKey but comment not found", e);
                if (comment.getStatus() != 2) return;
            }

            long score = comment.getCreateTime().toEpochSecond(java.time.ZoneOffset.UTC);
            stringRedisTemplate.opsForZSet().add(PostRedisKeys.userCommentZset(comment.getUserId()),
                    comment.getId().toString(), score);

            CommentModerationMsg modMsg = new CommentModerationMsg(
                    msg.getCommentId(), msg.getUserId(), msg.getPostId(),
                    msg.getParentId(), msg.getRootId());
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE,
                    RabbitMQConfig.KEY_COMMENT_MODERATION, modMsg);
            log.info("评论落库完成 commentId={}", msg.getCommentId());
        } catch (Exception e) {
            log.error("评论落库失败 msg={}", msg, e);
            throw new RuntimeException("评论落库失败，触发MQ重试", e);
        }
    }
}

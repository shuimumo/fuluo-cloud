package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.dto.CommentModerationMsg;
import com.shuimumu.fuluogame.dto.NotificationEvent;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.mapper.CommentMapper;
import com.shuimumu.fuluogame.utils.ContentModerationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CommentModerationConsumer {

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private ContentModerationUtil contentModerationUtil;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_COMMENT_MODERATION)
    public void handleComment(CommentModerationMsg msg) {
        try {
            Comment comment = commentMapper.selectById(msg.getCommentId());
            if (comment == null || comment.getStatus() != 2) return;

            ContentModerationUtil.ModerationResult result =
                    contentModerationUtil.moderateComment(comment.getContent());

            ContentModerationUtil.Action action = result.getAction();
            if (result.getDescription() == null || result.getDescription().isBlank()) {
                result.setDescription("内容未通过审核");
            }
            if (action == ContentModerationUtil.Action.RETRY) {
                throw new RuntimeException("审核接口异常，触发MQ重试");
            }
            if (action == ContentModerationUtil.Action.PASS) {
                comment.setStatus(0); comment.setModerationDesc(null); comment.setModerationWords(null);
                commentMapper.updateById(comment);
                // 缓存重建由 status 2->0 的 binlog 事件驱动。
            } else {
                if (action == ContentModerationUtil.Action.MANUAL_REVIEW) {
                    comment.setStatus(2);
                    log.warn("评论待人工审核 commentId={} desc={}", comment.getId(), result.getDescription());
                } else {
                    comment.setStatus(3);
                    log.warn("评论审核拒绝 commentId={} label={} desc={}", comment.getId(),
                            result.getLabel(), result.getDescription());
                }
                comment.setModerationDesc(result.getDescription());
                comment.setModerationWords(result.getRiskWords());
                commentMapper.updateById(comment);
                if (action != ContentModerationUtil.Action.MANUAL_REVIEW) {
                    rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                            RabbitMQConfig.NOTIFICATION_KEY,
                            NotificationEvent.system("moderation-comment:" + comment.getId(),
                                    comment.getUserId(), comment.getId(), comment.getPostId(),
                                    "你的评论未通过审核：" + result.getDescription()));
                }
            }
            log.info("评论审核完成 commentId={} action={}", comment.getId(), action);
        } catch (Exception e) {
            log.error("评论审核失败 msg={}", msg, e);
            throw new RuntimeException("审核处理失败，触发MQ重试", e);
        }
    }
}

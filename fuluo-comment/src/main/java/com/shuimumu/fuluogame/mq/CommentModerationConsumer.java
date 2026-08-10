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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评论审核消费者（批量）。
 * 使用独立批量监听容器（commentBatchListenerContainerFactory），一次接收一批消息，
 * 批量查库 + 逐条审核 + 批量回写状态，把原来"每条 1 次 SELECT + 1 次 UPDATE"降为"每批 1 次 SELECT + 1 次 UPDATE"。
 */
@Slf4j
@Component
public class CommentModerationConsumer {

    @Value("${fuluo.moderation.enabled:true}")
    private boolean moderationEnabled;


    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private ContentModerationUtil contentModerationUtil;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_COMMENT_MODERATION,
            containerFactory = "commentBatchListenerContainerFactory")
    public void handleComment(List<CommentModerationMsg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            // 1) 批量查库，只处理待审核（status=2）
            List<Long> ids = messages.stream().map(CommentModerationMsg::getCommentId)
                    .distinct().collect(Collectors.toList());
            Map<Long, Comment> commentById = new HashMap<>();
            List<Comment> comments = commentMapper.selectBatchIds(ids);
            if (comments != null) {
                for (Comment c : comments) {
                    commentById.put(c.getId(), c);
                }
            }

            List<Comment> toUpdate = new ArrayList<>();
            List<NotificationEvent> notifications = new ArrayList<>();

            // 2) 逐条审核（跳过模式直接 PASS；真实模式循环调阿里云单条接口）
            for (CommentModerationMsg msg : messages) {
                Comment comment = commentById.get(msg.getCommentId());
                if (comment == null || comment.getStatus() != 2) continue;

                ContentModerationUtil.ModerationResult result;
                if (!moderationEnabled) {
                    // 压测/本地调试：跳过阿里云 Green 审核，直接放行
                    result = new ContentModerationUtil.ModerationResult(
                            true, ContentModerationUtil.Action.PASS, "none", null, null, null, 0);
                } else {
                    result = contentModerationUtil.moderateComment(comment.getContent());
                }

                ContentModerationUtil.Action action = result.getAction();
                if (result.getDescription() == null || result.getDescription().isBlank()) {
                    result.setDescription("内容未通过审核");
                }
                if (action == ContentModerationUtil.Action.RETRY) {
                    throw new RuntimeException("审核接口异常，触发MQ重试");
                }
                if (action == ContentModerationUtil.Action.PASS) {
                    comment.setStatus(0); comment.setModerationDesc(null); comment.setModerationWords(null);
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
                    if (action != ContentModerationUtil.Action.MANUAL_REVIEW) {
                        notifications.add(NotificationEvent.system("moderation-comment:" + comment.getId(),
                                comment.getUserId(), comment.getId(), comment.getPostId(),
                                "你的评论未通过审核：" + result.getDescription()));
                    }
                }
                toUpdate.add(comment);
            }

            // 3) 批量回写审核状态（一次 UPDATE）
            if (!toUpdate.isEmpty()) {
                commentMapper.updateStatusBatch(toUpdate);
            }

            // 4) 拒绝通知（best-effort：失败记日志即可，重投也会因 status 变更被跳过）
            try {
                for (NotificationEvent event : notifications) {
                    rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                            RabbitMQConfig.NOTIFICATION_KEY, event);
                }
            } catch (Exception e) {
                log.error("评论批量审核通知发送失败（已记录，不重投）", e);
            }

            log.info("评论批量审核完成 batchSize={} updated={}", messages.size(), toUpdate.size());
        } catch (Exception e) {
            log.error("评论批量审核失败 batchSize={}", messages.size(), e);
            throw new RuntimeException("审核处理失败，触发MQ重试", e);
        }
    }
}


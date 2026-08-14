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

    // P0 修复：required=false，审核 SDK 未配置（aliyun.green.enabled!=true）时本字段为 null，
    // 下方审核逻辑走失败安全分支（保持待审核），避免无审核放行或服务启动失败。
    @Autowired(required = false)
    private ContentModerationUtil contentModerationUtil;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    // P2 修复（去 Canal）：审核通过后直接扇出评论缓存+互动通知，不再依赖 binlog->Canal
    @Autowired
    private ContentSyncPublisher contentSyncPublisher;

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
                    // 压测/本地调试：显式关闭审核，直接放行
                    result = new ContentModerationUtil.ModerationResult(
                            true, ContentModerationUtil.Action.PASS, "none", null, null, null, 0);
                } else if (contentModerationUtil == null) {
                    // P0 修复：审核开启但 SDK 未配置（密钥缺失）——失败安全，保持待审核，绝不无审核放行
                    log.error("审核SDK未配置，评论 {} 保持待审核", comment.getId());
                    result = new ContentModerationUtil.ModerationResult(
                            false, ContentModerationUtil.Action.MANUAL_REVIEW, "none", null,
                            "审核服务未配置", null, 0);
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

            // 4) 审核通过扇出：评论缓存重建 + 作者互动通知（原由 Canal binlog 驱动，现改为直接发）
            //    best-effort：失败仅记日志（重投会因 status!=2 被跳过），下游 sync.cache.comment / notification 各自队列重试。
            try {
                for (Comment comment : toUpdate) {
                    if (comment.getStatus() == 0) {
                        contentSyncPublisher.publishCommentApproved(comment.getId(),
                                comment.getUserId(), comment.getPostId());
                    }
                }
            } catch (Exception e) {
                log.error("评论批量审核扇出发送失败（DB 已更新，扇出待补偿）", e);
            }

            // 5) 拒绝通知（best-effort：失败记日志即可，重投也会因 status 变更被跳过）
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


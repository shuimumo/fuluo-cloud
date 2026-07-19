package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.dto.NotificationEvent;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.entity.Notification;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.User;
import com.shuimumu.fuluogame.mapper.CommentMapper;
import com.shuimumu.fuluogame.mapper.NotificationMapper;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Component
public class NotificationEventConsumer {

    private final NotificationMapper notificationMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;

    public NotificationEventConsumer(NotificationMapper notificationMapper, UserMapper userMapper,
                                     PostMapper postMapper, CommentMapper commentMapper) {
        this.notificationMapper = notificationMapper;
        this.userMapper = userMapper;
        this.postMapper = postMapper;
        this.commentMapper = commentMapper;
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
    public void consume(NotificationEvent event) {
        if (event == null || event.getEventId() == null || event.getEventId().isBlank()) {
            log.warn("忽略缺少 eventId 的通知事件: {}", event);
            return;
        }

        Notification notification = buildNotification(event);
        if (notification == null || notification.getRecipientId() == null) return;
        if (notification.getActorId() != null
                && notification.getActorId().equals(notification.getRecipientId())) {
            return;
        }

        try {
            notificationMapper.insert(notification);
            log.info("通知创建成功 eventId={} recipientId={} type={}",
                    event.getEventId(), notification.getRecipientId(), notification.getType());
        } catch (DuplicateKeyException ignored) {
            log.info("通知事件已消费，忽略重复消息 eventId={}", event.getEventId());
        }
    }

    private Notification buildNotification(NotificationEvent event) {
        Notification n = new Notification();
        n.setEventId(event.getEventId());
        n.setActorId(event.getActorId());
        n.setIsRead(false);
        n.setCreateTime(toLocalDateTime(event.getOccurredAt()));

        User actor = event.getActorId() != null ? userMapper.selectById(event.getActorId()) : null;
        if (actor != null) {
            n.setActorName(actor.getUsername());
            n.setActorAvatar(actor.getAvatar());
        } else if (event.getActorId() != null) {
            n.setActorName("用户" + event.getActorId());
        }

        return switch (event.getType()) {
            case NotificationEvent.POST_LIKE -> buildPostLike(n, event);
            case NotificationEvent.COMMENT_LIKE -> buildCommentLike(n, event);
            case NotificationEvent.COMMENT -> buildComment(n, event);
            case NotificationEvent.FOLLOW -> buildFollow(n, event);
            case NotificationEvent.SYSTEM -> buildSystem(n, event);
            default -> {
                log.warn("忽略未知通知类型 eventId={} type={}", event.getEventId(), event.getType());
                yield null;
            }
        };
    }

    private Notification buildPostLike(Notification n, NotificationEvent event) {
        Post post = postMapper.selectById(event.getTargetId());
        if (post == null || post.getStatus() == null || post.getStatus() != 0) return null;
        n.setRecipientId(post.getUserId());
        n.setType(NotificationEvent.POST_LIKE);
        n.setTargetType("POST");
        n.setTargetId(post.getId());
        n.setPostId(post.getId());
        n.setTargetTitle(trim(post.getTitle(), 200));
        n.setContent(trim(post.getContent(), 240));
        return n;
    }

    private Notification buildCommentLike(Notification n, NotificationEvent event) {
        Comment comment = commentMapper.selectById(event.getTargetId());
        if (comment == null || comment.getStatus() == null || comment.getStatus() != 0) return null;
        Post post = postMapper.selectById(comment.getPostId());
        n.setRecipientId(comment.getUserId());
        n.setType(NotificationEvent.COMMENT_LIKE);
        n.setTargetType("COMMENT");
        n.setTargetId(comment.getId());
        n.setPostId(comment.getPostId());
        n.setTargetTitle(post != null ? trim(post.getTitle(), 200) : null);
        n.setContent(trim(comment.getContent(), 240));
        return n;
    }

    private Notification buildComment(Notification n, NotificationEvent event) {
        Comment comment = commentMapper.selectById(event.getTargetId());
        if (comment == null || comment.getStatus() == null || comment.getStatus() != 0) return null;
        Post post = postMapper.selectById(comment.getPostId());
        if (post == null) return null;

        boolean reply = comment.getParentId() != null && comment.getParentId() > 0;
        if (reply) {
            Comment parent = commentMapper.selectById(comment.getParentId());
            if (parent == null) return null;
            n.setRecipientId(parent.getUserId());
            n.setType("REPLY");
        } else {
            n.setRecipientId(post.getUserId());
            n.setType(NotificationEvent.COMMENT);
        }
        n.setTargetType("COMMENT");
        n.setTargetId(comment.getId());
        n.setPostId(comment.getPostId());
        n.setTargetTitle(trim(post.getTitle(), 200));
        n.setContent(trim(comment.getContent(), 240));
        return n;
    }

    private Notification buildFollow(Notification n, NotificationEvent event) {
        n.setRecipientId(event.getTargetId());
        n.setType(NotificationEvent.FOLLOW);
        n.setTargetType("USER");
        n.setTargetId(event.getTargetId());
        return n;
    }

    private Notification buildSystem(Notification n, NotificationEvent event) {
        n.setRecipientId(event.getRecipientId());
        n.setType(NotificationEvent.SYSTEM);
        n.setTargetType("SYSTEM");
        n.setTargetId(event.getTargetId());
        n.setPostId(event.getPostId());
        n.setContent(trim(event.getMessage(), 500));
        return n;
    }

    private LocalDateTime toLocalDateTime(Long occurredAt) {
        if (occurredAt == null) return LocalDateTime.now();
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(occurredAt), ZoneId.systemDefault());
    }

    private String trim(String text, int maxLength) {
        if (text == null) return null;
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }
}

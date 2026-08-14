package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.dto.NotificationEvent;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * P2 修复（去 Canal）：把原 {@code CanalCdcConsumer} / {@code CommentCdcConsumer} 的扇出逻辑收敛到这里，
 * 由「审核消费者 / admin 直写」在 DB 状态落库后直接调用，省掉 binlog -> Canal -> canal.cdc 一跳。
 *
 * <p>只依赖 RabbitTemplate + StringRedisTemplate + 常量，不依赖 entity，因此放在 common 供各服务共享；
 * 用 @ConditionalOnProperty 保证未配置 MQ 的服务（如 fuluo-ai）不会实例化本组件，避免无 RabbitTemplate 而启动失败。
 */
@Component
@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")
public class ContentSyncPublisher {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 帖子审核通过（status 2->0）：缓存重建 + ES 索引 + 粉丝收件箱（大V 直接写 outbox，普通用户推 inbox）。
     * createTime 转秒级时间戳与旧 Canal 解析逻辑保持一致（UTC）。
     */
    public void publishPostApproved(Long postId, String title, String content,
                                    Long userId, LocalDateTime createTime) {
        long createTimeSeconds = createTime == null
                ? System.currentTimeMillis() / 1000
                : createTime.toEpochSecond(ZoneOffset.UTC);

        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_CACHE,
                Map.of("type", "post", "postId", postId));
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_ES_INDEX,
                Map.of("postId", postId, "title", title == null ? "" : title,
                        "content", content == null ? "" : content,
                        "userId", userId == null ? 0L : userId,
                        "createTime", createTimeSeconds));

        boolean isBigV = userId != null && Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                .isMember(UserRedisKeys.bigV(), userId.toString()));
        if (isBigV) {
            stringRedisTemplate.opsForZSet().add(UserRedisKeys.outbox(userId),
                    postId.toString(), createTimeSeconds);
            Long size = stringRedisTemplate.opsForZSet().zCard(UserRedisKeys.outbox(userId));
            if (size != null && size > UserRedisKeys.OUTBOX_MAX) {
                stringRedisTemplate.opsForZSet().removeRange(
                        UserRedisKeys.outbox(userId), 0, -(UserRedisKeys.OUTBOX_MAX + 1));
            }
        } else {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_INBOX,
                    Map.of("postId", postId, "authorId", userId == null ? 0L : userId,
                            "createTime", createTimeSeconds));
        }
    }

    /** 帖子删除（status -> 1）：ES 删除（帖子未索引过时是幂等 no-op）。 */
    public void publishPostDeleted(Long postId) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_ES_DELETE,
                Map.of("postId", postId));
    }

    /** 评论审核通过（status 2->0）：评论缓存重建 + 作者互动通知（通知帖子作者有人评论）。 */
    public void publishCommentApproved(Long commentId, Long userId, Long postId) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_CACHE_COMMENT,
                Map.of("type", "comment", "commentId", commentId));
        rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_KEY,
                NotificationEvent.interaction("comment:" + commentId, NotificationEvent.COMMENT,
                        userId, commentId, postId));
    }
}

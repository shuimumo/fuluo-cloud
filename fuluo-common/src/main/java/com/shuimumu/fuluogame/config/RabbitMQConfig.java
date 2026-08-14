package com.shuimumu.fuluogame.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")
public class RabbitMQConfig {

    public static final String EXCHANGE = "fuluo.sync";
    public static final String NOTIFICATION_EXCHANGE = "fuluo.notification";
    public static final String NOTIFICATION_QUEUE = "notification.events";
    public static final String NOTIFICATION_KEY = "notification.event";
    public static final String QUEUE_FOLLOW = "sync.follow";
    public static final String QUEUE_LIKE = "sync.like";
    public static final String KEY_FOLLOW = "follow";
    public static final String KEY_LIKE = "like";
    public static final String QUEUE_COMMENT_LIKE = "sync.comment.like";
    public static final String KEY_COMMENT_LIKE = "comment.like";
    public static final String QUEUE_COMMENT = "sync.comment";
    public static final String KEY_COMMENT = "comment";
    public static final String QUEUE_ES_INDEX = "es.post.index";
    public static final String QUEUE_ES_UPDATE = "es.post.update";
    public static final String QUEUE_ES_DELETE = "es.post.delete";
    public static final String KEY_ES_INDEX = "index";
    public static final String KEY_ES_UPDATE = "update";
    public static final String KEY_ES_DELETE = "delete";
    public static final String QUEUE_MODERATION = "sync.moderation";
    public static final String KEY_MODERATION = "moderation";
    public static final String QUEUE_COMMENT_MODERATION = "sync.comment.moderation";
    public static final String KEY_COMMENT_MODERATION = "comment.moderation";
    public static final String QUEUE_CACHE = "sync.cache";
    public static final String KEY_CACHE = "cache";
    public static final String QUEUE_INBOX = "sync.inbox";
    public static final String KEY_INBOX = "inbox";
    public static final String QUEUE_PERSISTENCE_POST = "sync.persistence.post";
    public static final String KEY_PERSISTENCE_POST = "persistence.post";
    public static final String QUEUE_PERSISTENCE_COMMENT = "sync.persistence.comment";
    public static final String KEY_PERSISTENCE_COMMENT = "persistence.comment";

    // ===== Canal CDC：独立 exchange，与 fuluo.sync 完全隔离。canal-server 直投到此 =====
    public static final String CDC_EXCHANGE = "canal.cdc";
    public static final String QUEUE_CDC = "cdc.post.change";
    public static final String KEY_CDC = "cdc.change";

    // 评论缓存重建（由 CanalCdcConsumer 扇出，comment 服务消费）
    public static final String QUEUE_CACHE_COMMENT = "sync.cache.comment";
    public static final String KEY_CACHE_COMMENT = "cache.comment";
    // 评论 CDC（comment 服务独立消费）
    public static final String QUEUE_CDC_COMMENT = "cdc.comment.change";

    // 非活跃用户重新活跃时，从关注者 user:posts 补拉帖子进 inbox
    public static final String QUEUE_BACKFILL = "sync.backfill";
    public static final String KEY_BACKFILL = "backfill";

    // ===== P1 修复：死信交换机/队列，毒消息治理 =====
    // 配合 nacos 公共配置 spring.rabbitmq.listener.simple.default-requeue-rejected=false：
    // 消费端重试 3 次仍失败的消息不再无限重投（此前 default-requeue-rejected=true 会让毒消息无限循环），
    // 而是由 broker 死信路由到 fuluo.dlx -> fuluo.dlx.queue。运维监控该队列深度即可发现毒消息，人工排查后重放。
    public static final String DLX_EXCHANGE = "fuluo.dlx";
    public static final String DLX_QUEUE = "fuluo.dlx.queue";
    public static final String DLX_KEY = "dead.letter";

    /** 所有业务队列统一挂死信：x-dead-letter-exchange -> DLX，routingKey -> DLX_KEY */
    private static final Map<String, Object> DLX_ARGS =
            Map.of("x-dead-letter-exchange", DLX_EXCHANGE,
                    "x-dead-letter-routing-key", DLX_KEY);

    @Bean
    public DirectExchange syncExchange() { return new DirectExchange(EXCHANGE); }

    @Bean
    public DirectExchange notificationExchange() { return new DirectExchange(NOTIFICATION_EXCHANGE); }

    // Canal 直投目标：TopicExchange，routing key 与 instance.properties 的 canal.mq.topic 一致
    @Bean
    public TopicExchange cdcExchange() { return new TopicExchange(CDC_EXCHANGE); }

    // 死信交换机 + 死信队列（只挂不消费，供人工排查）
    @Bean
    public DirectExchange deadLetterExchange() { return new DirectExchange(DLX_EXCHANGE); }
    @Bean
    public Queue deadLetterQueue() { return new Queue(DLX_QUEUE, true); }
    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(DLX_KEY);
    }

    @Bean
    public Queue cdcQueue() { return new Queue(QUEUE_CDC, true, false, false, DLX_ARGS); }
    @Bean
    public Binding cdcBinding() { return BindingBuilder.bind(cdcQueue()).to(cdcExchange()).with(KEY_CDC); }

    @Bean
    public Queue followQueue() { return new Queue(QUEUE_FOLLOW, true, false, false, DLX_ARGS); }
    @Bean
    public Queue likeQueue() { return new Queue(QUEUE_LIKE, true, false, false, DLX_ARGS); }
    @Bean
    public Queue commentLikeQueue() { return new Queue(QUEUE_COMMENT_LIKE, true, false, false, DLX_ARGS); }
    @Bean
    public Queue commentQueue() { return new Queue(QUEUE_COMMENT, true, false, false, DLX_ARGS); }
    @Bean
    public Queue esIndexQueue() { return new Queue(QUEUE_ES_INDEX, true, false, false, DLX_ARGS); }
    @Bean
    public Queue esUpdateQueue() { return new Queue(QUEUE_ES_UPDATE, true, false, false, DLX_ARGS); }
    @Bean
    public Queue esDeleteQueue() { return new Queue(QUEUE_ES_DELETE, true, false, false, DLX_ARGS); }
    @Bean
    public Queue moderationQueue() { return new Queue(QUEUE_MODERATION, true, false, false, DLX_ARGS); }
    @Bean
    public Queue commentModerationQueue() { return new Queue(QUEUE_COMMENT_MODERATION, true, false, false, DLX_ARGS); }
    @Bean
    public Queue cacheQueue() { return new Queue(QUEUE_CACHE, true, false, false, DLX_ARGS); }
    @Bean
    public Queue inboxQueue() { return new Queue(QUEUE_INBOX, true, false, false, DLX_ARGS); }
    @Bean
    public Queue postPersistenceQueue() { return new Queue(QUEUE_PERSISTENCE_POST, true, false, false, DLX_ARGS); }
    @Bean
    public Queue commentPersistenceQueue() { return new Queue(QUEUE_PERSISTENCE_COMMENT, true, false, false, DLX_ARGS); }

    @Bean
    public Binding followBinding() { return BindingBuilder.bind(followQueue()).to(syncExchange()).with(KEY_FOLLOW); }
    @Bean
    public Binding likeBinding() { return BindingBuilder.bind(likeQueue()).to(syncExchange()).with(KEY_LIKE); }
    @Bean
    public Binding commentLikeBinding() { return BindingBuilder.bind(commentLikeQueue()).to(syncExchange()).with(KEY_COMMENT_LIKE); }
    @Bean
    public Binding commentBinding() { return BindingBuilder.bind(commentQueue()).to(syncExchange()).with(KEY_COMMENT); }
    @Bean
    public Binding esIndexBinding() { return BindingBuilder.bind(esIndexQueue()).to(syncExchange()).with(KEY_ES_INDEX); }
    @Bean
    public Binding esUpdateBinding() { return BindingBuilder.bind(esUpdateQueue()).to(syncExchange()).with(KEY_ES_UPDATE); }
    @Bean
    public Binding esDeleteBinding() { return BindingBuilder.bind(esDeleteQueue()).to(syncExchange()).with(KEY_ES_DELETE); }
    @Bean
    public Binding moderationBinding() { return BindingBuilder.bind(moderationQueue()).to(syncExchange()).with(KEY_MODERATION); }
    @Bean
    public Binding commentModerationBinding() { return BindingBuilder.bind(commentModerationQueue()).to(syncExchange()).with(KEY_COMMENT_MODERATION); }
    @Bean
    public Binding cacheBinding() { return BindingBuilder.bind(cacheQueue()).to(syncExchange()).with(KEY_CACHE); }
    @Bean
    public Binding inboxBinding() { return BindingBuilder.bind(inboxQueue()).to(syncExchange()).with(KEY_INBOX); }
    @Bean
    public Binding postPersistenceBinding() { return BindingBuilder.bind(postPersistenceQueue()).to(syncExchange()).with(KEY_PERSISTENCE_POST); }
    @Bean
    public Binding commentPersistenceBinding() { return BindingBuilder.bind(commentPersistenceQueue()).to(syncExchange()).with(KEY_PERSISTENCE_COMMENT); }
    @Bean
    public Queue backfillQueue() { return new Queue(QUEUE_BACKFILL, true, false, false, DLX_ARGS); }
    @Bean
    public Binding backfillBinding() { return BindingBuilder.bind(backfillQueue()).to(syncExchange()).with(KEY_BACKFILL); }

    @Bean
    public Queue cacheCommentQueue() { return new Queue(QUEUE_CACHE_COMMENT, true, false, false, DLX_ARGS); }
    @Bean
    public Binding cacheCommentBinding() { return BindingBuilder.bind(cacheCommentQueue()).to(syncExchange()).with(KEY_CACHE_COMMENT); }

    @Bean
    public Queue cdcCommentQueue() { return new Queue(QUEUE_CDC_COMMENT, true, false, false, DLX_ARGS); }
    @Bean
    public Binding cdcCommentBinding() { return BindingBuilder.bind(cdcCommentQueue()).to(cdcExchange()).with(KEY_CDC); }

    @Bean
    public MessageConverter jacksonConverter() { return new Jackson2JsonMessageConverter(); }
}

package com.shuimumu.fuluogame.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class
RabbitMQConfig {

    public static final String EXCHANGE = "fuluo.sync";
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

    // 非活跃用户重新活跃时，从关注者 user:posts 补拉帖子进 inbox
    public static final String QUEUE_BACKFILL = "sync.backfill";
    public static final String KEY_BACKFILL = "backfill";

    @Bean
    public DirectExchange syncExchange() { return new DirectExchange(EXCHANGE); }

    // Canal 直投目标：TopicExchange，routing key 与 instance.properties 的 canal.mq.topic 一致
    @Bean
    public TopicExchange cdcExchange() { return new TopicExchange(CDC_EXCHANGE); }
    @Bean
    public Queue cdcQueue() { return new Queue(QUEUE_CDC, true); }
    @Bean
    public Binding cdcBinding() { return BindingBuilder.bind(cdcQueue()).to(cdcExchange()).with(KEY_CDC); }

    @Bean
    public Queue followQueue() { return new Queue(QUEUE_FOLLOW, true); }
    @Bean
    public Queue likeQueue() { return new Queue(QUEUE_LIKE, true); }
    @Bean
    public Queue commentLikeQueue() { return new Queue(QUEUE_COMMENT_LIKE, true); }
    @Bean
    public Queue commentQueue() { return new Queue(QUEUE_COMMENT, true); }
    @Bean
    public Queue esIndexQueue() { return new Queue(QUEUE_ES_INDEX, true); }
    @Bean
    public Queue esUpdateQueue() { return new Queue(QUEUE_ES_UPDATE, true); }
    @Bean
    public Queue esDeleteQueue() { return new Queue(QUEUE_ES_DELETE, true); }
    @Bean
    public Queue moderationQueue() { return new Queue(QUEUE_MODERATION, true); }
    @Bean
    public Queue commentModerationQueue() { return new Queue(QUEUE_COMMENT_MODERATION, true); }
    @Bean
    public Queue cacheQueue() { return new Queue(QUEUE_CACHE, true); }
    @Bean
    public Queue inboxQueue() { return new Queue(QUEUE_INBOX, true); }
    @Bean
    public Queue postPersistenceQueue() { return new Queue(QUEUE_PERSISTENCE_POST, true); }
    @Bean
    public Queue commentPersistenceQueue() { return new Queue(QUEUE_PERSISTENCE_COMMENT, true); }

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
    public Queue backfillQueue() { return new Queue(QUEUE_BACKFILL, true); }
    @Bean
    public Binding backfillBinding() { return BindingBuilder.bind(backfillQueue()).to(syncExchange()).with(KEY_BACKFILL); }

    @Bean
    public MessageConverter jacksonConverter() { return new Jackson2JsonMessageConverter(); }
}

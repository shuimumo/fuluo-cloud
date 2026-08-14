package com.shuimumu.fuluogame.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 帖子落库消费者专用批量监听容器（其余监听器仍用默认容器）。
 * 批量监听：consumerBatchEnabled + batchSize，一次投递一批消息，配合 upsert 批量写库。
 */
@Configuration
public class RabbitBatchConfig {

    @Bean("rabbitPostBatchExecutor")
    public Executor rabbitPostBatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("rabbit-post-batch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("postBatchListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory postBatchListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter,
            Executor rabbitPostBatchExecutor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setTaskExecutor(rabbitPostBatchExecutor);
        // P1 修复：毒消息治理。本批量工厂手工创建，不走 yaml 的 retry 配置，
        // 这里显式加 3 次重试（指数退避 1s/2s/4s），重试耗尽后 defaultRequeueRejected=false，
        // 消息交由队列挂载的 DLX 死信，避免毒消息无限重投拖垮消费者。
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                .build());
        factory.setDefaultRequeueRejected(false);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(200);
        factory.setPrefetchCount(250);
        factory.setBatchReceiveTimeout(2000L);
        factory.setConcurrentConsumers(4);
        factory.setMaxConcurrentConsumers(8);
        return factory;
    }
}
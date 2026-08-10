package com.shuimumu.fuluogame.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 评论审核消费者专用批量监听容器（其余监听器仍用默认容器）。
 * 批量监听：consumerBatchEnabled + batchSize，一次投递一批消息，配合批量查库/回写状态。
 */
@Configuration
public class CommentBatchConfig {

    @Bean("commentBatchListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory commentBatchListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(200);
        factory.setPrefetchCount(250);
        factory.setBatchReceiveTimeout(2000L);
        factory.setConcurrentConsumers(4);
        factory.setMaxConcurrentConsumers(8);
        return factory;
    }
}

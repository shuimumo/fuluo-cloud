package com.shuimumu.fuluogame.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationRabbitConfig {

    @Bean
    public Queue notificationQueue() {
        return new Queue(RabbitMQConfig.NOTIFICATION_QUEUE, true);
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(new org.springframework.amqp.core.DirectExchange(RabbitMQConfig.NOTIFICATION_EXCHANGE))
                .with(RabbitMQConfig.NOTIFICATION_KEY);
    }
}

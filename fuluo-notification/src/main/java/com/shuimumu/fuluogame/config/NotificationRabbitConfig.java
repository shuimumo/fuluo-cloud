package com.shuimumu.fuluogame.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class NotificationRabbitConfig {

    @Bean
    public Queue notificationQueue() {
        // P1 修复：挂死信。毒消息重试耗尽后进入 DLX（fuluo.dlx），避免无限重投或被丢弃
        return new Queue(RabbitMQConfig.NOTIFICATION_QUEUE, true, false, false,
                Map.of("x-dead-letter-exchange", RabbitMQConfig.DLX_EXCHANGE,
                        "x-dead-letter-routing-key", RabbitMQConfig.DLX_KEY));
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(new org.springframework.amqp.core.DirectExchange(RabbitMQConfig.NOTIFICATION_EXCHANGE))
                .with(RabbitMQConfig.NOTIFICATION_KEY);
    }
}

package com.shuimumu.fuluogame.config;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Canal CDC 消费者专用监听容器。
 *
 * <p>Canal flatMessage 投递到 {@code canal.cdc} 的消息 content-type 是 text/plain，
 * 默认容器使用 Jackson2JsonMessageConverter 解析时会刷
 * “Could not convert incoming message with content-type [text/plain]” 的 WARN。
 * 两个 CDC 消费者本就用原始 {@link org.springframework.amqp.core.Message} 手工解析，
 * 因此这里用 {@link SimpleRabbitListenerContainerFactoryConfigurer} 复用 yaml 中的
 * concurrency/prefetch/retry 等配置，仅把消息转换器换成 SimpleMessageConverter，
 * 消除 WARN 且不改变消费逻辑。
 */
@Configuration
public class CdcListenerConfig {

    @Bean("cdcListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory cdcListenerContainerFactory(
            ConnectionFactory connectionFactory,
            SimpleRabbitListenerContainerFactoryConfigurer configurer) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        // 覆盖默认的 Jackson 转换器：CDC 消息是 text/plain，直接按原始字节处理
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }
}

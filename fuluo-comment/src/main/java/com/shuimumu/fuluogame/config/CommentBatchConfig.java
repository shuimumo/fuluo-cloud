package com.shuimumu.fuluogame.config;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 评论审核消费者专用批量监听容器（其余监听器仍用默认容器）。
 * 批量监听：consumerBatchEnabled + batchSize，一次投递一批消息，配合批量查库/回写状态。
 */
@Configuration
public class CommentBatchConfig {

    // 与 fuluo-post 的 RabbitBatchConfig 对齐：批量消费者用独立线程池，避免与普通监听器争抢默认线程。
    @Bean("rabbitCommentBatchExecutor")
    public Executor rabbitCommentBatchExecutor() {
        // 与 fuluo-post 相同的修复：批量监听容器在 consumer 退出重启时会向 executor 提交新任务，
        // ThreadPoolTaskExecutor(core=4/queue=200) 会让重启任务排队、CallerRunsPolicy 占住容器启动线程，
        // 导致周期性 "Consumer failed to start in 60000 milliseconds"、审核吞吐塌陷（实测 ~7/s）。
        // SimpleAsyncTaskExecutor 不排队、consumer 长生命周期，恢复批量吞吐。
        return new SimpleAsyncTaskExecutor("rabbit-comment-batch-");
    }

    @Bean("commentBatchListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory commentBatchListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter,
            Executor rabbitCommentBatchExecutor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setTaskExecutor(rabbitCommentBatchExecutor);
        // P1 修复：毒消息治理。批量监听不走 yaml 的 retry 配置，这里显式加 3 次重试（指数退避），
        // 重试耗尽后 defaultRequeueRejected=false，消息交由队列挂载的 DLX 死信，避免无限重投。
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

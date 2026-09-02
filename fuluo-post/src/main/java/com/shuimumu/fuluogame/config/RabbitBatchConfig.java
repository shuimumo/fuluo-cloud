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
 * 帖子落库消费者专用批量监听容器（其余监听器仍用默认容器）。
 * 批量监听：consumerBatchEnabled + batchSize，一次投递一批消息，配合 upsert 批量写库。
 *
 * <p>落库与审核两个批量消费者各自独立线程池 + 独立容器工厂，避免共用线程池时
 * 一个容器占满 core 线程导致另一个容器消费者线程排队饿死
 * （此前共用 rabbitPostBatchExecutor(core=4) 时 sync.persistence.post 无消费者，
 * 启动报 "Consumer failed to start in 60000 milliseconds"）。
 */
@Configuration
public class RabbitBatchConfig {

    /** 落库消费者线程池：并发拉满追写库吞吐。 */
    @Bean("rabbitPostPersistenceExecutor")
    public Executor rabbitPostPersistenceExecutor() {
        return buildBatchExecutor("rabbit-post-persist-");
    }

    /** 审核消费者线程池：独立于落库，后续可按阿里云 QPS 配额单独调并发。 */
    @Bean("rabbitPostModerationExecutor")
    public Executor rabbitPostModerationExecutor() {
        return buildBatchExecutor("rabbit-post-moderation-");
    }

    private Executor buildBatchExecutor(String threadNamePrefix) {
        // 不用 ThreadPoolTaskExecutor：批量监听容器在 consumer 退出重启时会向 executor 提交新任务，
        // core=4/queue=200 会让重启任务排队，CallerRunsPolicy 又让容器启动线程被 consumer.run() 占住，
        // 导致容器反复 "Consumer failed to start in 60000 milliseconds"、每 60s 才处理一批（吞吐塌陷）。
        // SimpleAsyncTaskExecutor 每个 consumer 一个线程、不排队，consumer 又是长生命周期，
        // 不会创建销毁风暴；08-10 压测 3000/s 即为其默认行为。这里给两个批量消费者各配独立实例，
        // 线程名前缀区分，互不影响。
        return new SimpleAsyncTaskExecutor(threadNamePrefix);
    }

    @Bean("postPersistenceListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory postPersistenceListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter,
            Executor rabbitPostPersistenceExecutor) {
        return buildBatchFactory(connectionFactory, messageConverter, rabbitPostPersistenceExecutor);
    }

    @Bean("postModerationListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory postModerationListenerContainerFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter,
            Executor rabbitPostModerationExecutor) {
        return buildBatchFactory(connectionFactory, messageConverter, rabbitPostModerationExecutor);
    }

    private SimpleRabbitListenerContainerFactory buildBatchFactory(
            ConnectionFactory connectionFactory, MessageConverter messageConverter, Executor executor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setTaskExecutor(executor);
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

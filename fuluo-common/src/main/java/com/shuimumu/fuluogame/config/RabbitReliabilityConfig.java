package com.shuimumu.fuluogame.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 发布端可靠性配置（P1 修复）。
 *
 * <p>依赖 Nacos 公共配置（fuluo-common.yaml）中的：</p>
 * <pre>
 *   spring.rabbitmq.publisher-confirm-type: correlated
 *   spring.rabbitmq.publisher-returns: true
 *   spring.rabbitmq.template.mandatory: true
 * </pre>
 *
 * <p>解决的问题：</p>
 * <ol>
 *   <li>{@code ConfirmCallback}：correlated 模式下每条消息到达 broker 后触发；ack=false 表示
 *       broker 拒绝（极少数），记录 correlationData 与原因，避免"broker 静默拒绝"的盲区。</li>
 *   <li>{@code ReturnsCallback}：mandatory=true 时，消息若无任何队列可路由则被退回。
 *       此前（LOADTEST 报告实测 {@code routed:false}）broker 会静默丢弃、API 却仍返回成功；
 *       现在会记录 exchange/routingKey/消息体，让"假成功"变为可观测。</li>
 * </ol>
 *
 * <p>说明：回调当前只做日志与可观测（含消息体，便于人工重放）；自动化补偿（死信重放/落库重试）
 * 可作为后续增强接入。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "spring.rabbitmq", name = "host")
public class RabbitReliabilityConfig {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @PostConstruct
    public void registerCallbacks() {
        // 确认回调：ack=false 表示 broker 拒绝（非"未路由"），消息可能丢失，需人工补偿
        rabbitTemplate.setConfirmCallback((CorrelationData correlationData, boolean ack, String cause) -> {
            if (!ack) {
                log.error("[MQ确认失败] correlationData={}, cause={}，消息可能丢失，需人工补偿",
                        correlationData != null ? correlationData.getId() : null, cause);
            }
        });

        // 退回回调：mandatory 消息无法路由到任何队列时被退回（此前被静默丢弃）
        rabbitTemplate.setReturnsCallback((ReturnedMessage returned) -> {
            log.error("[MQ未路由退回] exchange={}, routingKey={}, replyCode={}, replyText={}, body={}。"
                            + "消息未进入任何队列，需人工补偿",
                    returned.getExchange(), returned.getRoutingKey(),
                    returned.getReplyCode(), returned.getReplyText(),
                    returned.getMessage() != null ? new String(returned.getMessage().getBody()) : null);
        });
    }
}

package com.shuimumu.fuluogame.mq;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 评论 CDC 消费者：监听 canal-server 直投到 canal.cdc exchange 的 comment 表 binlog 变更。
 * 评论审核通过（2→0）时扇出到 {@link RabbitMQConfig#QUEUE_CACHE_COMMENT} 触发缓存重建。
 */
@Slf4j
@Component
public class CommentCdcConsumer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CDC_COMMENT)
    public void handleCdc(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JSONObject msg = JSONUtil.parseObj(body);
            if (Boolean.TRUE.equals(msg.getBool("isDdl", false))) return;

            String type = msg.getStr("type");
            JSONArray data = msg.getJSONArray("data");
            JSONArray old = msg.getJSONArray("old");
            if (data == null || data.isEmpty()) return;
            if (!"UPDATE".equals(type)) return;
            if (old == null || old.isEmpty()) return;

            for (int i = 0; i < data.size(); i++) {
                JSONObject after = data.getJSONObject(i);
                JSONObject before = i < old.size() ? old.getJSONObject(i) : null;
                if (before == null || !before.containsKey("status")) continue;

                String beforeStatus = before.getStr("status");
                String afterStatus = after.getStr("status");
                // comment：2→0 审核通过 → 扇出缓存重建
                if ("2".equals(beforeStatus) && "0".equals(afterStatus)) {
                    Long commentId = after.getLong("id");
                    rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_CACHE_COMMENT,
                            Map.of("type", "comment", "commentId", commentId));
                    log.info("CDC 评论审核通过扇出 commentId={}", commentId);
                }
            }
        } catch (Exception e) {
            log.error("CommentCdcConsumer 处理失败 body={}", body, e);
            throw new RuntimeException("CDC 处理失败，触发MQ重试", e);
        }
    }
}

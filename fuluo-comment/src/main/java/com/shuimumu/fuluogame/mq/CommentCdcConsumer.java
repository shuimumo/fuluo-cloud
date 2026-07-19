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
 * Derives comment cache updates from MySQL status changes emitted by Canal.
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
            if (!"UPDATE".equals(msg.getStr("type"))) return;
            if (!"comment".equals(msg.getStr("table"))) return;

            JSONArray data = msg.getJSONArray("data");
            JSONArray old = msg.getJSONArray("old");
            if (data == null || data.isEmpty() || old == null || old.isEmpty()) return;

            for (int i = 0; i < data.size(); i++) {
                JSONObject before = i < old.size() ? old.getJSONObject(i) : null;
                if (before == null || !before.containsKey("status")) continue;

                JSONObject after = data.getJSONObject(i);
                if ("2".equals(before.getStr("status")) && "0".equals(after.getStr("status"))) {
                    Long commentId = after.getLong("id");
                    rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_CACHE_COMMENT,
                            Map.of("type", "comment", "commentId", commentId));
                    log.info("Canal comment approved commentId={}", commentId);
                }
            }
        } catch (Exception e) {
            log.error("Canal comment CDC processing failed body={}", body, e);
            throw new RuntimeException("CDC processing failed; trigger MQ retry", e);
        }
    }
}

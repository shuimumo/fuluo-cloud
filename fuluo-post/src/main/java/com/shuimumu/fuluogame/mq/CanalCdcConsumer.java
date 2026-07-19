package com.shuimumu.fuluogame.mq;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Derives post cache, search, and feed updates from MySQL status changes emitted by Canal.
 */
@Slf4j
@Component
public class CanalCdcConsumer {

    private static final DateTimeFormatter BINLOG_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_CDC)
    public void handleCdc(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JSONObject msg = JSONUtil.parseObj(body);
            if (Boolean.TRUE.equals(msg.getBool("isDdl", false))) return;
            if (!"UPDATE".equals(msg.getStr("type"))) return;
            if (!"post".equals(msg.getStr("table"))) return;

            JSONArray data = msg.getJSONArray("data");
            JSONArray old = msg.getJSONArray("old");
            if (data == null || data.isEmpty() || old == null || old.isEmpty()) return;

            for (int i = 0; i < data.size(); i++) {
                JSONObject before = i < old.size() ? old.getJSONObject(i) : null;
                if (before == null || !before.containsKey("status")) continue;

                JSONObject after = data.getJSONObject(i);
                handlePostUpdate(after, before.getStr("status"), after.getStr("status"));
            }
        } catch (Exception e) {
            log.error("Canal post CDC processing failed body={}", body, e);
            throw new RuntimeException("CDC processing failed; trigger MQ retry", e);
        }
    }

    private void handlePostUpdate(JSONObject after, String beforeStatus, String afterStatus) {
        Long postId = after.getLong("id");
        if ("2".equals(beforeStatus) && "0".equals(afterStatus)) {
            long createTime = parseEpochSecond(after.getStr("create_time"));
            Long userId = after.getLong("user_id");

            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_CACHE,
                    Map.of("type", "post", "postId", postId));
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_ES_INDEX,
                    Map.of("postId", postId, "title", after.getStr("title"),
                            "content", after.getStr("content"), "userId", userId,
                            "createTime", createTime));

            boolean isBigV = Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(UserRedisKeys.bigV(), userId.toString()));
            if (isBigV) {
                stringRedisTemplate.opsForZSet().add(UserRedisKeys.outbox(userId),
                        postId.toString(), createTime);
                Long size = stringRedisTemplate.opsForZSet().zCard(UserRedisKeys.outbox(userId));
                if (size != null && size > UserRedisKeys.OUTBOX_MAX) {
                    stringRedisTemplate.opsForZSet().removeRange(
                            UserRedisKeys.outbox(userId), 0, -(UserRedisKeys.OUTBOX_MAX + 1));
                }
            } else {
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_INBOX,
                        Map.of("postId", postId, "authorId", userId, "createTime", createTime));
            }
            log.info("Canal post approved postId={}", postId);
        } else if ("1".equals(afterStatus)) {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_ES_DELETE,
                    Map.of("postId", postId));
            log.info("Canal post deleted postId={}", postId);
        }
    }

    private long parseEpochSecond(String createTime) {
        if (createTime == null || createTime.isEmpty()) {
            return System.currentTimeMillis() / 1000;
        }
        try {
            return LocalDateTime.parse(createTime, BINLOG_DT).toEpochSecond(ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("Cannot parse binlog create_time; using current time createTime={}", createTime);
            return System.currentTimeMillis() / 1000;
        }
    }
}

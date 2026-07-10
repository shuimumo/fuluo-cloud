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
 * Canal CDC 消费者：监听 canal-server 直投到 canal.cdc exchange 的 binlog 变更，
 * 对 post/comment 表做列值 diff，派生下游动作并复用现有 fuluo.sync 路由键扇出。
 *
 * 仅接管"status 状态机落库后的扇出"（窄边界）：
 *   - post   UPDATE 2→0 → cache + es.index + inbox/outbox
 *   - post   UPDATE *→1 → es.delete（逻辑删除清 ES）
 *   - comment UPDATE 2→0 → cache
 * INSERT→moderation 仍由 PostPersistenceConsumer 发（需带 topicIds）；
 * isNew 的一次性 stats/topic 自增仍留在 PostModerationConsumer（binlog 无此语义）。
 *
 * Canal FlatMessage 结构：
 *   { "type":"UPDATE", "database":"fuluogame", "table":"post",
 *     "data":[{变更后的整行}], "old":[{仅包含被修改的列的变更前值}] }
 * 故 status 由 2→0：old[i].status=="2" && data[i].status=="0"。
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
        // 用 Message 接收原始 body，绕过全局 Jackson2JsonMessageConverter，
        // 避免 canal 投递的 content-type 与 converter 反序列化目标类型冲突。
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JSONObject msg = JSONUtil.parseObj(body);
            // canal DDL 等事件无 data，直接跳过
            if (Boolean.TRUE.equals(msg.getBool("isDdl", false))) return;

            String type = msg.getStr("type");          // INSERT/UPDATE/DELETE
            String table = msg.getStr("table");
            JSONArray data = msg.getJSONArray("data");
            JSONArray old = msg.getJSONArray("old");    // UPDATE 才有，且只含被改的列
            if (data == null || data.isEmpty()) return;

            if (!"UPDATE".equals(type)) return;         // 窄边界：只关心 status 变迁
            if (old == null || old.isEmpty()) return;

            for (int i = 0; i < data.size(); i++) {
                JSONObject after = data.getJSONObject(i);
                JSONObject before = i < old.size() ? old.getJSONObject(i) : null;
                if (before == null || !before.containsKey("status")) continue; // status 未变，忽略

                String beforeStatus = before.getStr("status");
                String afterStatus = after.getStr("status");
                if ("post".equals(table)) {
                    handlePostUpdate(after, beforeStatus, afterStatus);
                } else if ("comment".equals(table)) {
                    handleCommentUpdate(after, beforeStatus, afterStatus);
                }
            }
        } catch (Exception e) {
            log.error("CanalCdcConsumer 处理失败 body={}", body, e);
            throw new RuntimeException("CDC 处理失败，触发MQ重试", e);
        }
    }

    /** post：2→0 审核通过扇出；*→1 逻辑删除清 ES */
    private void handlePostUpdate(JSONObject after, String beforeStatus, String afterStatus) {
        Long postId = after.getLong("id");
        if ("2".equals(beforeStatus) && "0".equals(afterStatus)) {
            long createTime = parseEpochSecond(after.getStr("create_time"));
            Long userId = after.getLong("user_id");
            // 1. 缓存 ZSET + 单帖缓存重建
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_CACHE,
                    Map.of("type", "post", "postId", postId));
            // 2. ES 索引
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_ES_INDEX,
                    Map.of("postId", postId, "title", after.getStr("title"),
                            "content", after.getStr("content"), "userId", userId,
                            "createTime", createTime));
            // 3. 收件箱扇出：BigV 写 outbox，普通用户推 inbox（逻辑平移自 PostModerationConsumer）
            boolean isBigV = Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(UserRedisKeys.bigV(), userId.toString()));
            if (isBigV) {
                stringRedisTemplate.opsForZSet().add(UserRedisKeys.outbox(userId),
                        postId.toString(), createTime);
                Long size = stringRedisTemplate.opsForZSet().zCard(UserRedisKeys.outbox(userId));
                if (size != null && size > UserRedisKeys.OUTBOX_MAX) {
                    stringRedisTemplate.opsForZSet().removeRange(
                            UserRedisKeys.outbox(userId), 0,
                            -(UserRedisKeys.OUTBOX_MAX + 1));
                }
            } else {
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_INBOX,
                        Map.of("postId", postId, "authorId", userId, "createTime", createTime));
            }
            log.info("CDC 帖子审核通过扇出 postId={}", postId);
        } else if ("1".equals(afterStatus)) {
            // 逻辑删除 → 清 ES
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_ES_DELETE,
                    Map.of("postId", postId));
            log.info("CDC 帖子逻辑删除清 ES postId={}", postId);
        }
    }

    /** comment：2→0 审核通过 → 缓存 ZSET + 单条缓存重建 */
    private void handleCommentUpdate(JSONObject after, String beforeStatus, String afterStatus) {
        if ("2".equals(beforeStatus) && "0".equals(afterStatus)) {
            Long commentId = after.getLong("id");
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_CACHE,
                    Map.of("type", "comment", "commentId", commentId));
            log.info("CDC 评论审核通过扇出 commentId={}", commentId);
        }
    }

    /**
     * binlog 的 create_time 是 "yyyy-MM-dd HH:mm:ss" 字符串。
     * 按 LocalDateTime + toEpochSecond(UTC) 解析，与现有消费者打分约定保持一致（ZSET score 对齐）。
     */
    private long parseEpochSecond(String createTime) {
        if (createTime == null || createTime.isEmpty()) {
            return System.currentTimeMillis() / 1000;
        }
        try {
            return LocalDateTime.parse(createTime, BINLOG_DT).toEpochSecond(ZoneOffset.UTC);
        } catch (Exception e) {
            log.warn("create_time 解析失败，回退当前时间 createTime={}", createTime);
            return System.currentTimeMillis() / 1000;
        }
    }
}

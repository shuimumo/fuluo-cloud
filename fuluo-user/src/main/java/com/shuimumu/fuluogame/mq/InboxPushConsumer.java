package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 收件箱推送：仅推给活跃粉丝。大V（在 user:bigv）走拉模式，
 * 由 InboxBackfillConsumer 在粉丝活跃补拉时处理，不在此推送。
 */
@Slf4j
@Component
public class InboxPushConsumer {

    /** 批量收件箱推送 Lua（P2 修复）：一次往返写全部活跃粉丝 */
    private static final DefaultRedisScript<Long> INBOX_PUSH_BATCH_SCRIPT = new DefaultRedisScript<>();

    static {
        INBOX_PUSH_BATCH_SCRIPT.setLocation(new ClassPathResource("lua/inbox_push_batch.lua"));
        INBOX_PUSH_BATCH_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_INBOX)
    public void handleInbox(Map<String, Object> msg) {
        try {
            Long postId = Long.valueOf(msg.get("postId").toString());
            Long authorId = Long.valueOf(msg.get("authorId").toString());
            long createTime = Long.parseLong(msg.getOrDefault("createTime", "0").toString());
            if (createTime == 0) createTime = System.currentTimeMillis() / 1000;

            String authorIdStr = authorId.toString();
            // 大V走拉模式，不发 inbox
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                    .isMember(UserRedisKeys.bigV(), authorIdStr))) {
                return;
            }

            String followersKey = UserRedisKeys.followers(authorId);
            Long followerCount = stringRedisTemplate.opsForSet().size(followersKey);
            if (followerCount == null || followerCount == 0) return;

            // 服务端求活跃粉丝交集（ZSET active:users ∩ SET followers）
            // 混合类型合法：SET 成员视 score=1，结果 ZSET 仅含活跃粉丝
            String destKey = "tmp:push:active:" + authorId + ":" + postId;
            stringRedisTemplate.opsForZSet().intersectAndStore(
                    UserRedisKeys.activeUsers(), List.of(followersKey), destKey);
            Set<String> activeFollowers = stringRedisTemplate.opsForZSet().range(destKey, 0, -1);
            stringRedisTemplate.delete(destKey);

            if (activeFollowers == null || activeFollowers.isEmpty()) return;

            String postIdStr = postId.toString();
            String scoreStr = String.valueOf(createTime);
            // P2 修复：批量收件箱推送，一次 Lua 往返写全部活跃粉丝（原逐粉丝一次 execute 为 N 次往返）
            List<String> inboxKeys = new ArrayList<>();
            for (String fid : activeFollowers) {
                inboxKeys.add(UserRedisKeys.inbox(Long.parseLong(fid)));
            }
            stringRedisTemplate.execute(INBOX_PUSH_BATCH_SCRIPT, inboxKeys,
                    postIdStr, scoreStr, String.valueOf(UserRedisKeys.INBOX_MAX));
            log.info("收件箱推送完成 postId={} authorId={} 活跃粉丝数={}/{}",
                    postId, authorId, activeFollowers.size(), followerCount);
        } catch (Exception e) {
            log.error("InboxPushConsumer 处理失败 msg={}", msg, e);
            throw new RuntimeException("收件箱推送失败，触发MQ重试", e);
        }
    }
}

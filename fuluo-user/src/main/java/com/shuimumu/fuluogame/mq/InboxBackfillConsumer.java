package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 非活跃用户重新登录时，从关注的非大V作者 user:posts ZSET 补拉帖子进自己 inbox。
 * 大V帖走拉模式（读时归并 outbox），不补拉。
 */
@Slf4j
@Component
public class InboxBackfillConsumer {

    private static final DefaultRedisScript<Long> INBOX_PUSH_SCRIPT = new DefaultRedisScript<>();
    private static final int PER_AUTHOR_MAX = 50;
    private static final int TOTAL_MAX = 2000;

    static {
        INBOX_PUSH_SCRIPT.setLocation(new ClassPathResource("lua/inbox_push.lua"));
        INBOX_PUSH_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_BACKFILL)
    public void handleBackfill(Map<String, Object> msg) {
        try {
            Long userId = Long.valueOf(msg.get("userId").toString());
            long sinceTs = Long.parseLong(msg.getOrDefault("sinceTs", "0").toString());

            Set<String> following = stringRedisTemplate.opsForSet()
                    .members(UserRedisKeys.following(userId));
            if (following == null || following.isEmpty()) return;

            String inboxKey = UserRedisKeys.inbox(userId);
            String postIdStr, scoreStr;
            int total = 0;

            for (String authorIdStr : following) {
                if (total >= TOTAL_MAX) break;
                Long authorId = Long.parseLong(authorIdStr);
                // 大V帖走拉，不补
                if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                        .isMember(UserRedisKeys.bigV(), authorIdStr))) {
                    continue;
                }

                String sourceKey = PostRedisKeys.userPostsZset(authorId);
                Set<TypedTuple<String>> posts = stringRedisTemplate.opsForZSet()
                        .rangeByScoreWithScores(sourceKey, sinceTs,
                                Double.POSITIVE_INFINITY, 0, PER_AUTHOR_MAX);
                if (posts == null || posts.isEmpty()) continue;

                for (TypedTuple<String> t : posts) {
                    if (total >= TOTAL_MAX) break;
                    postIdStr = t.getValue();
                    scoreStr = String.valueOf(t.getScore().longValue());
                    stringRedisTemplate.execute(INBOX_PUSH_SCRIPT,
                            List.of(inboxKey), postIdStr, scoreStr);
                    total++;
                }
            }
            log.info("补拉完成 userId={} sinceTs={} 作者数={} 补入帖数={}",
                    userId, sinceTs, following.size(), total);
        } catch (Exception e) {
            log.error("InboxBackfillConsumer 处理失败 msg={}", msg, e);
            throw new RuntimeException("补拉失败，触发MQ重试", e);
        }
    }
}

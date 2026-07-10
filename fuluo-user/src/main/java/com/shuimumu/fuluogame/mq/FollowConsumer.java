package com.shuimumu.fuluogame.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.dto.SyncMessage;
import com.shuimumu.fuluogame.entity.Follow;
import com.shuimumu.fuluogame.mapper.FollowMapper;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class FollowConsumer {

    @Autowired
    private FollowMapper followMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_FOLLOW)
    public void handleFollow(SyncMessage msg) {
        try {
            Follow existing = followMapper.selectOne(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getFollowerId, msg.getUserId())
                    .eq(Follow::getFollowedId, msg.getTargetId()));
            if ("follow".equals(msg.getType())) {
                if (existing != null) {
                    if (existing.getStatus() != 1) {
                        existing.setStatus(1);
                        followMapper.updateById(existing);
                    }
                } else {
                    try {
                        Follow f = new Follow();
                        f.setFollowerId(msg.getUserId());
                        f.setFollowedId(msg.getTargetId());
                        f.setStatus(1);
                        followMapper.insert(f);
                    } catch (org.springframework.dao.DuplicateKeyException e) {
                        log.warn("Follow duplicate insert, retrying as update");
                        Follow dup = followMapper.selectOne(new LambdaQueryWrapper<Follow>()
                                .eq(Follow::getFollowerId, msg.getUserId())
                                .eq(Follow::getFollowedId, msg.getTargetId()));
                        if (dup != null && dup.getStatus() != 1) {
                            dup.setStatus(1);
                            followMapper.updateById(dup);
                        }
                    }
                }

                // 关注成功 → 回溯最近帖子到 inbox
                {
                    String targetIdStr = msg.getTargetId().toString();
                    String inboxKey = UserRedisKeys.inbox(msg.getUserId());

                    // 大V：从 outbox 拉
                    boolean isBigV = Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                            .isMember(UserRedisKeys.bigV(), targetIdStr));
                    String sourceKey = isBigV
                            ? UserRedisKeys.outbox(msg.getTargetId())
                            : PostRedisKeys.userPostsZset(msg.getTargetId());

                    Set<String> recentPosts = stringRedisTemplate.opsForZSet().reverseRange(sourceKey, 0, 19);
                    if (recentPosts != null && !recentPosts.isEmpty()) {
                        for (String pid : recentPosts) {
                            Double score = stringRedisTemplate.opsForZSet().score(sourceKey, pid);
                            stringRedisTemplate.opsForZSet().add(inboxKey, pid, score != null ? score : 0);
                        }
                    }
                }
            } else if ("unfollow".equals(msg.getType())) {
                if (existing != null && existing.getStatus() == 1) {
                    existing.setStatus(0);
                    followMapper.updateById(existing);
                }
            }
            log.info("FollowConsumer 处理完成 type={} userId={} targetId={}",
                    msg.getType(), msg.getUserId(), msg.getTargetId());
        } catch (Exception e) {
            log.error("FollowConsumer 处理失败 msg={}", msg, e);
            throw e;
        }
    }
}

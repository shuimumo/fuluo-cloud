package com.shuimumu.fuluogame.task;

import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.entity.UserStat;
import com.shuimumu.fuluogame.mapper.UserStatMapper;
import com.shuimumu.fuluogame.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户域定时同步（从单体 {@code StatsSyncTask} 拆出的用户部分）：用户统计入库 + 清理非活跃用户。
 *
 * <p><strong>HA 加固</strong>：微服务多实例下 {@code @Scheduled} 会在每个实例各跑一次，
 * 造成重复 upsert。这里用 Redisson 分布式锁 {@code tryLock(0,...)} 抢主，仅一个实例执行。
 */
@Slf4j
@Component
public class UserStatsSyncTask {

    private static final int BATCH_SIZE = 500;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserStatMapper userStatMapper;
    @Autowired
    private RedissonClient redissonClient;

    @Scheduled(cron = "0 * * * * ?")
    public void syncDirty() {
        runExclusive("lock:sync:userStats", 55, this::syncUserStats);
    }

    @Scheduled(cron = "0 17 4 * * ?")
    public void cleanInactiveUsers() {
        runExclusive("lock:clean:inactiveUsers", 120, () -> {
            long cutoff = System.currentTimeMillis() / 1000 - UserRedisKeys.ACTIVE_WINDOW_SEC;
            stringRedisTemplate.opsForZSet().removeRangeByScore(UserRedisKeys.activeUsers(), 0, cutoff);
        });
    }

    private void syncUserStats() {
        List<UserStat> batch = new ArrayList<>();
        forEachDirty(UserRedisKeys.statsDirty(), userIdStr -> {
            Long userId = Long.parseLong(userIdStr);
            Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(UserRedisKeys.stats(userId));
            if (map.isEmpty()) return;

            UserStat stat = new UserStat();
            stat.setUserId(userId);
            stat.setPosts(RedisUtils.toLong(map.get("posts")));
            stat.setFollowing(RedisUtils.toLong(map.get("following")));
            stat.setFollowers(RedisUtils.toLong(map.get("followers")));
            stat.setLikes(RedisUtils.toLong(map.get("likes")));
            batch.add(stat);

            if (stat.getFollowers() >= 1000) {
                stringRedisTemplate.opsForSet().add(UserRedisKeys.bigV(), userIdStr);
            } else {
                stringRedisTemplate.opsForSet().remove(UserRedisKeys.bigV(), userIdStr);
            }
        }, () -> {
            userStatMapper.upsertBatch(batch);
            batch.clear();
        });
    }

    // ==================== 通用 ====================

    /** 抢到分布式锁才执行，避免多实例重复跑；抢不到直接跳过。 */
    private void runExclusive(String lockKey, long leaseSeconds, Runnable task) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS);
            if (!locked) return;
            task.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("定时任务执行失败 {}", lockKey, e);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private void forEachDirty(String key, java.util.function.Consumer<String> handler, Runnable flush) {
        while (true) {
            List<String> members = stringRedisTemplate.opsForSet().pop(key, BATCH_SIZE);
            if (members == null || members.isEmpty()) break;
            List<String> failed = new ArrayList<>();
            for (String member : members) {
                try {
                    handler.accept(member);
                } catch (Exception e) {
                    log.error("同步失败 {} {}", key, member, e);
                    failed.add(member);
                }
            }
            try {
                flush.run();
            } catch (Exception e) {
                log.error("flush失败 {} 回写{}条", key, members.size(), e);
                stringRedisTemplate.opsForSet().add(key, members.toArray(new String[0]));
                continue;
            }
            if (!failed.isEmpty()) {
                stringRedisTemplate.opsForSet().add(key, failed.toArray(new String[0]));
            }
        }
    }
}

package com.shuimumu.fuluogame.task;

import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.entity.UserStat;
import com.shuimumu.fuluogame.mapper.UserStatMapper;
import com.shuimumu.fuluogame.utils.RedisUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 用户域定时同步：用户统计入库 + 清理不活跃用户。
 *
 * <p><strong>分片多锁</strong>：{@code user:stats:dirty:{shard}} 每片独立锁，多实例可并行。
 * <p><strong>dirty 安全</strong>：不 SPOP；落库且快照未变才 SREM。
 */
@Slf4j
@Component
public class UserStatsSyncTask {

    private static final int BATCH_SIZE = 500;

    private final ExecutorService shardExecutor = Executors.newFixedThreadPool(
            Math.max(2, UserRedisKeys.STATS_DIRTY_SHARD_COUNT),
            r -> {
                Thread t = new Thread(r, "user-dirty-shard");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private UserStatMapper userStatMapper;
    @Autowired
    private RedissonClient redissonClient;

    @PreDestroy
    public void shutdown() {
        shardExecutor.shutdown();
        try {
            shardExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Scheduled(cron = "0 * * * * ?")
    public void syncDirty() {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int shard = 0; shard < UserRedisKeys.STATS_DIRTY_SHARD_COUNT; shard++) {
            final int s = shard;
            futures.add(CompletableFuture.runAsync(
                    () -> runExclusive("lock:sync:userStats:" + s, 50,
                            () -> syncUserStats(UserRedisKeys.statsDirtyShard(s))),
                    shardExecutor));
        }
        // 兼容旧版未分片 dirty
        futures.add(CompletableFuture.runAsync(
                () -> runExclusive("lock:sync:userStats:legacy", 50,
                        () -> syncUserStats(UserRedisKeys.statsDirtyLegacy())),
                shardExecutor));
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    @Scheduled(cron = "0 17 4 * * ?")
    public void cleanInactiveUsers() {
        runExclusive("lock:clean:inactiveUsers", 120, () -> {
            long cutoff = System.currentTimeMillis() / 1000 - UserRedisKeys.ACTIVE_WINDOW_SEC;
            stringRedisTemplate.opsForZSet().removeRangeByScore(UserRedisKeys.activeUsers(), 0, cutoff);
        });
    }

    private void syncUserStats(String dirtyKey) {
        drainDirty(dirtyKey, ids -> {
            List<UserStat> batch = new ArrayList<>();
            Map<String, Map<Object, Object>> snapshots = new HashMap<>();
            List<String> removable = new ArrayList<>();

            for (String userIdStr : ids) {
                Long userId = Long.parseLong(userIdStr);
                Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(UserRedisKeys.stats(userId));
                if (map == null || map.isEmpty()) {
                    removable.add(userIdStr);
                    continue;
                }
                snapshots.put(userIdStr, new HashMap<>(map));

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
            }

            if (!batch.isEmpty()) {
                userStatMapper.upsertBatch(batch);
            }

            for (Map.Entry<String, Map<Object, Object>> e : snapshots.entrySet()) {
                String userIdStr = e.getKey();
                Long userId = Long.parseLong(userIdStr);
                Map<Object, Object> current = stringRedisTemplate.opsForHash().entries(UserRedisKeys.stats(userId));
                if (hashEquals(e.getValue(), current)) {
                    removable.add(userIdStr);
                }
            }
            return removable;
        });
    }

    // ==================== 通用 ====================

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

    private void drainDirty(String dirtyKey, DirtyBatchHandler handler) {
        while (true) {
            List<String> sampled = stringRedisTemplate.opsForSet().randomMembers(dirtyKey, BATCH_SIZE);
            if (sampled == null || sampled.isEmpty()) {
                break;
            }
            List<String> members = new ArrayList<>(new LinkedHashSet<>(sampled));
            List<String> removable;
            try {
                removable = handler.handle(members);
            } catch (Exception e) {
                log.error("dirty 批处理失败 key={} size={}", dirtyKey, members.size(), e);
                break;
            }
            if (removable == null || removable.isEmpty()) {
                break;
            }
            stringRedisTemplate.opsForSet().remove(dirtyKey, removable.toArray());
        }
    }

    private static boolean hashEquals(Map<Object, Object> a, Map<Object, Object> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (Map.Entry<Object, Object> e : a.entrySet()) {
            Object key = e.getKey();
            if (!b.containsKey(key)) return false;
            String va = e.getValue() == null ? null : e.getValue().toString();
            Object bv = b.get(key);
            String vb = bv == null ? null : bv.toString();
            if (!Objects.equals(va, vb)) return false;
        }
        return true;
    }

    @FunctionalInterface
    private interface DirtyBatchHandler {
        List<String> handle(List<String> members);
    }
}

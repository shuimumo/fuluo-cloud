package com.shuimumu.fuluogame.task;

import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.mapper.TopicMapper;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 帖子域定时同步：帖子计数入库 + 话题统计入库 + 热度衰减。
 *
 * <p><strong>分片多锁</strong>：dirty 按 id 拆成 {@link PostRedisKeys#DIRTY_SHARD_COUNT} 片，
 * 每片独立 Redis key 与 Redisson 锁；多实例可并行消费不同分片，单实例内分片也并行处理。
 * <p><strong>dirty 安全</strong>：不 SPOP。先取样落库，确认 Redis 快照未再变化后才 SREM。
 */
@Slf4j
@Component
public class PostStatsSyncTask {

    private static final int BATCH_SIZE = 500;

    private final ExecutorService shardExecutor = Executors.newFixedThreadPool(
            Math.max(2, PostRedisKeys.DIRTY_SHARD_COUNT),
            r -> {
                Thread t = new Thread(r, "post-dirty-shard");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private TopicMapper topicMapper;
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

        // 帖子计数分片
        for (int shard = 0; shard < PostRedisKeys.DIRTY_SHARD_COUNT; shard++) {
            final int s = shard;
            futures.add(CompletableFuture.runAsync(
                    () -> runExclusive("lock:sync:postCounts:" + s, 50,
                            () -> syncPostCounts(PostRedisKeys.dirtyShard(s))),
                    shardExecutor));
        }
        // 话题统计分片
        for (int shard = 0; shard < PostRedisKeys.DIRTY_SHARD_COUNT; shard++) {
            final int s = shard;
            futures.add(CompletableFuture.runAsync(
                    () -> runExclusive("lock:sync:topics:" + s, 50,
                            () -> syncTopics(PostRedisKeys.topicDirtyShard(s))),
                    shardExecutor));
        }
        // 兼容旧版未分片 dirty（历史残留，消费完即空）
        futures.add(CompletableFuture.runAsync(
                () -> runExclusive("lock:sync:postCounts:legacy", 50,
                        () -> syncPostCounts(PostRedisKeys.dirtyLegacy())),
                shardExecutor));
        futures.add(CompletableFuture.runAsync(
                () -> runExclusive("lock:sync:topics:legacy", 50,
                        () -> syncTopics(PostRedisKeys.topicDirtyLegacy())),
                shardExecutor));

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    @Scheduled(cron = "0 0 4 * * ?")
    public void decayHotScores() {
        runExclusive("lock:decay:hot", 300, () -> {
            Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(PostRedisKeys.hotZset(), 0, -1);
            if (members == null || members.isEmpty()) return;
            for (String pid : members) {
                Double score = stringRedisTemplate.opsForZSet().score(PostRedisKeys.hotZset(), pid);
                if (score != null && score > 0.01) {
                    stringRedisTemplate.opsForZSet().add(PostRedisKeys.hotZset(), pid, score * 0.75);
                }
            }
        });
    }

    private void syncPostCounts(String dirtyKey) {
        drainDirty(dirtyKey, ids -> {
            List<Post> batch = new ArrayList<>();
            Map<String, Map<Object, Object>> snapshots = new HashMap<>();
            List<String> removable = new ArrayList<>();

            for (String postIdStr : ids) {
                Long postId = Long.parseLong(postIdStr);
                Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(PostRedisKeys.counts(postId));
                if (map == null || map.isEmpty()) {
                    removable.add(postIdStr);
                    continue;
                }
                snapshots.put(postIdStr, copyHash(map));
                Post post = new Post();
                post.setId(postId);
                post.setLikes((int) RedisUtils.toLong(map.get("likes")));
                post.setComments((int) RedisUtils.toLong(map.get("comments")));
                post.setViews((int) RedisUtils.toLong(map.get("views")));
                batch.add(post);
            }

            if (!batch.isEmpty()) {
                postMapper.upsertBatch(batch);
            }

            for (Map.Entry<String, Map<Object, Object>> e : snapshots.entrySet()) {
                String postIdStr = e.getKey();
                Long postId = Long.parseLong(postIdStr);
                Map<Object, Object> current = stringRedisTemplate.opsForHash().entries(PostRedisKeys.counts(postId));
                if (hashEquals(e.getValue(), current)) {
                    removable.add(postIdStr);
                }
            }
            return removable;
        });
    }

    private void syncTopics(String dirtyKey) {
        drainDirty(dirtyKey, ids -> {
            List<Topic> batch = new ArrayList<>();
            Map<String, Map<Object, Object>> snapshots = new HashMap<>();
            List<String> removable = new ArrayList<>();

            for (String idStr : ids) {
                Long topicId = Long.parseLong(idStr);
                Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(PostRedisKeys.topic(topicId));
                if (map == null || map.isEmpty()) {
                    removable.add(idStr);
                    continue;
                }
                snapshots.put(idStr, copyHash(map));
                Topic topic = new Topic();
                topic.setId(topicId);
                topic.setTotalViews(RedisUtils.toLong(map.get("views")));
                topic.setTotalPosts(RedisUtils.toLong(map.get("posts")));
                batch.add(topic);
            }

            if (!batch.isEmpty()) {
                topicMapper.upsertBatch(batch);
            }

            for (Map.Entry<String, Map<Object, Object>> e : snapshots.entrySet()) {
                String idStr = e.getKey();
                Long topicId = Long.parseLong(idStr);
                Map<Object, Object> current = stringRedisTemplate.opsForHash().entries(PostRedisKeys.topic(topicId));
                if (hashEquals(e.getValue(), current)) {
                    removable.add(idStr);
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

    /**
     * 安全消费 dirty Set：SRANDMEMBER 取样 → 落库 → 仅对成功且未再变化的成员 SREM。
     */
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

    private static Map<Object, Object> copyHash(Map<Object, Object> map) {
        return new HashMap<>(map);
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

package com.shuimumu.fuluogame.task;

import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.mapper.TopicMapper;
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
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 帖子域定时同步（从单体 {@code StatsSyncTask} 拆出的帖子部分）：
 * 帖子计数入库 + 话题统计入库 + 热度衰减。
 *
 * <p><strong>HA 加固</strong>：多实例下用 Redisson 分布式锁抢主，仅一个实例执行，
 * 避免重复 upsert 与热度被多次衰减。
 */
@Slf4j
@Component
public class PostStatsSyncTask {

    private static final int BATCH_SIZE = 500;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private TopicMapper topicMapper;
    @Autowired
    private RedissonClient redissonClient;

    @Scheduled(cron = "0 * * * * ?")
    public void syncDirty() {
        runExclusive("lock:sync:postCounts", 55, this::syncPostCounts);
        runExclusive("lock:sync:topics", 55, this::syncTopics);
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

    private void syncPostCounts() {
        List<Post> batch = new ArrayList<>();
        forEachDirty(PostRedisKeys.dirty(), postIdStr -> {
            Long postId = Long.parseLong(postIdStr);
            Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(PostRedisKeys.counts(postId));
            if (map.isEmpty()) return;

            Post post = new Post();
            post.setId(postId);
            post.setLikes((int) RedisUtils.toLong(map.get("likes")));
            post.setComments((int) RedisUtils.toLong(map.get("comments")));
            post.setViews((int) RedisUtils.toLong(map.get("views")));
            batch.add(post);
        }, () -> {
            postMapper.upsertBatch(batch);
            batch.clear();
        });
    }

    private void syncTopics() {
        List<Topic> batch = new ArrayList<>();
        forEachDirty(PostRedisKeys.topicDirty(), idStr -> {
            Long topicId = Long.parseLong(idStr);
            Map<Object, Object> map = stringRedisTemplate.opsForHash().entries(PostRedisKeys.topic(topicId));
            if (map.isEmpty()) return;

            Topic topic = new Topic();
            topic.setId(topicId);
            topic.setTotalViews(RedisUtils.toLong(map.get("views")));
            topic.setTotalPosts(RedisUtils.toLong(map.get("posts")));
            batch.add(topic);
        }, () -> {
            topicMapper.upsertBatch(batch);
            batch.clear();
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

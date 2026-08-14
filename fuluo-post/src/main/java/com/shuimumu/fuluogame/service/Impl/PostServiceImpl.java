package com.shuimumu.fuluogame.service.Impl;

import cn.hutool.json.JSONUtil;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.dto.PostDTO;
import com.shuimumu.fuluogame.dto.PostPersistMsg;
import com.shuimumu.fuluogame.dto.SyncMessage;
import com.shuimumu.fuluogame.entity.Post;

import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.mapper.PostMapper;

import com.shuimumu.fuluogame.service.PostService;
import com.shuimumu.fuluogame.client.UserProfileFetcher;
import com.shuimumu.fuluogame.utils.CacheClient;
import com.shuimumu.fuluogame.utils.PostAssembler;
import com.shuimumu.fuluogame.utils.PostFeedSupport;
import com.shuimumu.fuluogame.utils.UserStatsRedisHelper;
import com.shuimumu.fuluogame.utils.PostBloomFilter;
import com.shuimumu.fuluogame.utils.RedisHashCounter;
import com.shuimumu.fuluogame.utils.RedisIdWorker;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;
import com.shuimumu.fuluogame.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PostServiceImpl implements PostService {

    private static final DefaultRedisScript<Long> LIKE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> UNLIKE_SCRIPT = new DefaultRedisScript<>();

    static {
        LIKE_SCRIPT.setLocation(new ClassPathResource("lua/like.lua"));
        LIKE_SCRIPT.setResultType(Long.class);
        UNLIKE_SCRIPT.setLocation(new ClassPathResource("lua/unlike.lua"));
        UNLIKE_SCRIPT.setResultType(Long.class);
    }

        /**
     * 确保 post:counts:{postId} 的 likes/comments/views 齐全。
     * 缺失字段从 DB 读取后通过 HSETNX 补齐，避免 putAll 覆盖并发 HINCRBY 增量。
     * 任何 HINCRBY 之前调用，防止空 key 从 0 开始累加。
     * <p>仅在发生播种（字段补齐）时设置 TTL；字段已齐全的快路径不续期，
     * 让长期无互动的帖子计数自然过期。真正的计数变更由写路径 refreshTtl。
     */
    private void ensurePostCounts(Long postId) {
        String key = PostRedisKeys.counts(postId);
        Long hlen = stringRedisTemplate.opsForHash().size(key);
        if (hlen != null && hlen >= 3) return; // likes/comments/views 已齐全
        Post post = postMapper.selectById(postId);
        if (post == null) return;
        RedisHashCounter.initPostCounts(
                stringRedisTemplate,
                key,
                post.getLikes() != null ? post.getLikes() : 0,
                post.getComments() != null ? post.getComments() : 0,
                post.getViews() != null ? post.getViews() : 0);
        // 从 DB 播种后设置初始 TTL
        RedisHashCounter.refreshTtl(stringRedisTemplate, key,
                PostRedisKeys.POST_COUNTS_TTL, java.util.concurrent.TimeUnit.DAYS);
    }

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostAssembler postAssembler;

    @Autowired
    private PostFeedSupport postFeedSupport;

    @Autowired
    private UserProfileFetcher userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private PostBloomFilter postBloomFilter;

    @Autowired
    private UserStatsRedisHelper userStatsRedisHelper;

    @Autowired
    private RabbitTemplate rabbitTemplate;



    @Autowired
    private RedisIdWorker redisIdWorker;

    @Override
    public void publishPost(PostDTO postDTO) {
        Long authorId = UserContext.getUser().getId();
        Long postId = redisIdWorker.nextId("post");
        postBloomFilter.add(postId);
        PostPersistMsg msg = new PostPersistMsg(postId, authorId, true,
                postDTO.getTitle(), postDTO.getContent(),
                postDTO.getImages() != null && !postDTO.getImages().isEmpty()
                        ? JSONUtil.toJsonStr(postDTO.getImages()) : null,
                postDTO.getTopicIds() != null && !postDTO.getTopicIds().isEmpty()
                        ? postDTO.getTopicIds().stream().map(String::valueOf)
                            .collect(Collectors.joining(",")) : null);
        // P1 修复：携带 CorrelationData，便于 RabbitReliabilityConfig 的 Confirm 回调定位失败消息
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_PERSISTENCE_POST, msg,
                new CorrelationData("post:" + postId));
    }

    @Override
    public PageVO<PostContentVO> getPostList(String tab, int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        long start = (long) (page - 1) * size;
        long end = start + size - 1;

        // 热门流：仅 Redis ZSET（不降级 DB）
        if ("hot".equals(tab)) {
            String zsetKey = PostRedisKeys.hotZset();
            Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
            if (members == null || members.isEmpty()) {
                return PageVO.of(List.of(), 0, page, size);
            }
            List<PostContentVO> records = postFeedSupport.loadAndAssemble(postFeedSupport.membersToIds(members));
            Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
            return PageVO.of(records, total != null ? total : 0, page, size);
        }

        // 最新流：ZSET 窗口内走 Redis，窗口外/空窗口降级 MySQL
        String latestKey = PostRedisKeys.latestZset();
        Long zcard = stringRedisTemplate.opsForZSet().zCard(latestKey);
        long window = zcard != null ? zcard : 0L;
        List<Long> postIds;
        if (window > 0 && start < window) {
            Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(latestKey, start, end);
            postIds = postFeedSupport.membersToIds(members);
        } else {
            postIds = postMapper.selectLatestIds(start, size);
            if (postIds == null) postIds = List.of();
        }
        List<PostContentVO> records = postFeedSupport.loadAndAssemble(postIds);
        long total = postMapper.countPublished();
        return PageVO.of(records, total, page, size);
    }

    @Override
    public PostContentVO getPostById(Long id) {
        if (!postBloomFilter.mightContain(id)) return null;
        String cacheKey = SystemRedisKeys.cachePostContent(id);
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if ("".equals(json)) return null;
        PostContentVO vo = null;
        if (json != null) {
            vo = JSONUtil.toBean(json, PostContentVO.class);
            stringRedisTemplate.expire(cacheKey, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
        } else {
            String lockKey = "lock:" + cacheKey;
            vo = cacheClient.queryWithMutex(lockKey, cacheKey, PostContentVO.class, () -> {
                Post post = postMapper.selectById(id);
                if (post == null) return null;
                postBloomFilter.add(id);
                if (post.getStatus() == 0) return postAssembler.toVO(post);
                if (post.getStatus() != 1) {
                    Long currentUserId = null;
                    try { currentUserId = UserContext.getUser().getId(); } catch (Exception ignored) {}
                    if (post.getUserId().equals(currentUserId)) return postAssembler.toVO(post);
                }
                return null;
            }, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
        }
        if (vo == null) return null;
        postAssembler.fillUsers(List.of(vo),
                userService.batchGetProfiles(Set.of(vo.getUserId())));
        ensurePostCounts(id);
        stringRedisTemplate.opsForHash().increment(PostRedisKeys.counts(id), "views", 1);
        // 计数变化：刷新 post:counts TTL
        RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.counts(id),
                PostRedisKeys.POST_COUNTS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(id), id.toString());
        Set<String> topicIds = stringRedisTemplate.opsForSet().members(PostRedisKeys.postTopicIds(id));
        if (topicIds != null) {
            for (String tid : topicIds) {
                Long topicId = Long.parseLong(tid);
                stringRedisTemplate.opsForHash().increment(PostRedisKeys.topic(topicId), "views", 1);
                RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.topic(topicId),
                        PostRedisKeys.TOPIC_STATS_TTL, TimeUnit.DAYS);
                stringRedisTemplate.opsForSet().add(PostRedisKeys.topicDirty(Long.parseLong(tid)), tid);
            }
        }
        postAssembler.fillCounts(List.of(vo));
        postAssembler.fillStatus(List.of(vo));
        return vo;
    }

    @Override
    public void updatePost(Long postId, PostDTO postDTO) {
        Post post = postMapper.selectById(postId);
        if (post == null) throw new RuntimeException("帖子不存在");
        if (!post.getUserId().equals(UserContext.getUser().getId())) {
            throw new RuntimeException("无权编辑该帖子");
        }
        if (post.getStatus() == 1) throw new RuntimeException("帖子已删除，无法编辑");
        PostPersistMsg msg = new PostPersistMsg(postId, UserContext.getUser().getId(), false,
                postDTO.getTitle(), postDTO.getContent(),
                postDTO.getImages() != null ? JSONUtil.toJsonStr(postDTO.getImages()) : null,
                null);
        // P1 修复：携带 CorrelationData，便于 RabbitReliabilityConfig 的 Confirm 回调定位失败消息
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_PERSISTENCE_POST, msg,
                new CorrelationData("post:" + postId));
    }

    @Override
    public void deletePost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) throw new RuntimeException("帖子不存在");
        if (!post.getUserId().equals(UserContext.getUser().getId())) {
            throw new RuntimeException("无权删除该帖子");
        }
        int oldStatus = post.getStatus();
        post.setStatus(1);
        postMapper.updateById(post);
        // 从公开/用户 ZSET 中移除
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.latestZset(), postId.toString());
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.hotZset(), postId.toString());
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.userPostsZset(post.getUserId()), postId.toString());
        // 话题 ZSET
        Set<String> topicIds = stringRedisTemplate.opsForSet().members(PostRedisKeys.postTopicIds(postId));
        if (topicIds != null && !topicIds.isEmpty()) {
            for (String tid : topicIds) {
                stringRedisTemplate.opsForZSet().remove(PostRedisKeys.topicPostsLatestZset(Long.parseLong(tid)), postId.toString());
                stringRedisTemplate.opsForZSet().remove(PostRedisKeys.topicPostsHotZset(Long.parseLong(tid)), postId.toString());
                if (oldStatus == 0) {
                    Long topicId = Long.parseLong(tid);
                    stringRedisTemplate.opsForHash().increment(PostRedisKeys.topic(topicId), "posts", -1);
                    RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.topic(topicId),
                            PostRedisKeys.TOPIC_STATS_TTL, TimeUnit.DAYS);
                    stringRedisTemplate.opsForSet().add(PostRedisKeys.topicDirty(Long.parseLong(tid)), tid);
                }
            }
        }
        stringRedisTemplate.delete(PostRedisKeys.postTopicIds(postId));
        cacheClient.delete(SystemRedisKeys.cachePostContent(postId));
        // 仅当原状态为公开时扣减作者发帖数
        if (oldStatus == 0) {
            // 扣减前 ensure，避免 stats 过期后从 0 减成负数错账
            userStatsRedisHelper.ensureUserStats(post.getUserId());
            stringRedisTemplate.opsForHash().increment(UserRedisKeys.stats(post.getUserId()), "posts", -1);
            stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(post.getUserId()), post.getUserId().toString());
        }
        // ES 删除由 status -> 1 的 binlog 事件驱动。
    }

    // ========== 点赞 / 取消点赞 ==========

    @Override
    public boolean like(Long userId, Long postId, Long authorId) {
        // 在 Lua 执行前确保计数 key 存在，避免 HINCRBY 从 0 开始
        ensurePostCounts(postId);
        userStatsRedisHelper.ensureUserStats(authorId);
        List<String> keys = List.of(
                PostRedisKeys.likes(postId),
                UserRedisKeys.stats(authorId),
                UserRedisKeys.liked(userId),
                PostRedisKeys.counts(postId)
        );
        Long result = stringRedisTemplate.execute(LIKE_SCRIPT, keys,
                userId.toString(), postId.toString());
        if (result == null || result == 0) return false;
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_LIKE,
                new SyncMessage("like", userId, postId, authorId));
        stringRedisTemplate.expire(UserRedisKeys.stats(authorId),
                UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(authorId), authorId.toString());
        stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(postId), postId.toString());
        // 点赞变更了 likes：刷新 post:counts TTL
        RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.counts(postId),
                PostRedisKeys.POST_COUNTS_TTL, TimeUnit.DAYS);
        return true;
    }

    @Override
    public boolean unlike(Long userId, Long postId, Long authorId) {
        // 在 Lua 执行前确保计数 key 存在，避免 HINCRBY 从 0 开始
        ensurePostCounts(postId);
        // Lua 会 HINCRBY 作者 likes，先 ensure user:stats
        userStatsRedisHelper.ensureUserStats(authorId);
        List<String> keys = List.of(
                PostRedisKeys.likes(postId),
                UserRedisKeys.stats(authorId),
                UserRedisKeys.liked(userId),
                PostRedisKeys.counts(postId)
        );
        Long result = stringRedisTemplate.execute(UNLIKE_SCRIPT, keys,
                userId.toString(), postId.toString());
        if (result == null || result == 0) return false;
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_LIKE,
                new SyncMessage("unlike", userId, postId, authorId));
        stringRedisTemplate.expire(UserRedisKeys.stats(authorId),
                UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(authorId), authorId.toString());
        stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(postId), postId.toString());
        // 点赞变更了 likes：刷新 post:counts TTL
        RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.counts(postId),
                PostRedisKeys.POST_COUNTS_TTL, TimeUnit.DAYS);
        return true;
    }

    @Override
    public Long getPostAuthorId(Long id) {
        String json = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cachePostContent(id));
        if (json != null && !json.isEmpty()) {
            return JSONUtil.parseObj(json).getLong("userId");
        }
        Post post = postMapper.selectById(id);
        return post != null ? post.getUserId() : null;
    }

//       ========== ZSET 重建 ==========
//
//    public void rebuildZsets() {
//        List<Post> all = postMapper.selectList(
//                new LambdaQueryWrapper<Post>().eq(Post::getStatus, 0));
//        for (Post p : all) {
//            long latestScore = p.getCreateTime().toEpochSecond(java.time.ZoneOffset.UTC);
//            stringRedisTemplate.opsForZSet().add(PostRedisKeys.latestZset(), p.getId().toString(), latestScore);
//            double days = Math.max(0.1, (System.currentTimeMillis() -
//                    p.getCreateTime().toEpochSecond(java.time.ZoneOffset.UTC) * 1000L) / 86400000.0);
//            double hotScore = (p.getLikes() * 0.7 + p.getComments() * 0.3) / days;
//            stringRedisTemplate.opsForZSet().add(PostRedisKeys.hotZset(), p.getId().toString(), hotScore);
//        }
//        log.info("ZSET 重建完成，latest={}，hot={}", all.size(), all.size());
//    }

}

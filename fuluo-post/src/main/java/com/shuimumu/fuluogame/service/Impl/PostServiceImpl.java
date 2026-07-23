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
import com.shuimumu.fuluogame.utils.PostBloomFilter;
import com.shuimumu.fuluogame.utils.RedisHashCounter;
import com.shuimumu.fuluogame.utils.RedisIdWorker;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;
import com.shuimumu.fuluogame.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
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
    }

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private PostAssembler postAssembler;

    @Autowired
    private UserProfileFetcher userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private PostBloomFilter postBloomFilter;

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
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_PERSISTENCE_POST, msg);
    }

    @Override
    public PageVO<PostContentVO> getPostList(String tab, int page, int size) {
        String zsetKey = "hot".equals(tab) ? PostRedisKeys.hotZset() : PostRedisKeys.latestZset();
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0, page, size);
        }
        List<Long> postIds = members.stream().map(Long::parseLong).collect(Collectors.toList());
        // 鎵归噺鏌ュ崟甯栫紦瀛?
        List<String> cacheKeys = postIds.stream()
                .map(SystemRedisKeys::cachePostContent).collect(Collectors.toList());
        List<String> cachedJsons = stringRedisTemplate.opsForValue().multiGet(cacheKeys);
        Map<Long, PostContentVO> resultMap = new LinkedHashMap<>();
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < postIds.size(); i++) {
            Long pid = postIds.get(i);
            String json = cachedJsons != null ? cachedJsons.get(i) : null;
            if (json != null && !json.isEmpty()) {
                resultMap.put(pid, JSONUtil.toBean(json, PostContentVO.class));
                stringRedisTemplate.expire(SystemRedisKeys.cachePostContent(pid),
                        PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            } else {
                missIds.add(pid);
            }
        }
        // miss 鐨勫姞閿侀槻鍑荤┛
        for (Long pid : missIds) {
            String dataKey = SystemRedisKeys.cachePostContent(pid);
            PostContentVO vo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, PostContentVO.class, () -> {
                Post p = postMapper.selectById(pid);
                if (p == null || p.getStatus() != 0) return null;
                return postAssembler.toVO(p);
            }, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            if (vo != null) resultMap.put(pid, vo);
        }
        // 淇濇寔 ZSET 椤哄簭杈撳嚭
        List<PostContentVO> records = new ArrayList<>();
        for (Long pid : postIds) {
            PostContentVO vo = resultMap.get(pid);
            if (vo != null) records.add(vo);
        }
        postAssembler.fillUsers(records, userService.batchGetProfiles(
                records.stream().map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet())));
        postAssembler.fillCounts(records);
        postAssembler.fillStatus(records);
        Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
        return PageVO.of(records, total != null ? total : 0, page, size);
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
        // 娴忚閲?鈥?纭繚璁℃暟 key 瀛樺湪鍐嶇疮鍔狅紝閬垮厤 TTL 杩囨湡鍚庝粠 0 寮€濮?
        ensurePostCounts(id);
        stringRedisTemplate.opsForHash().increment(PostRedisKeys.counts(id), "views", 1);
        stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(), id.toString());
        Set<String> topicIds = stringRedisTemplate.opsForSet().members(PostRedisKeys.postTopicIds(id));
        if (topicIds != null) {
            for (String tid : topicIds) {
                stringRedisTemplate.opsForHash().increment(PostRedisKeys.topic(Long.parseLong(tid)), "views", 1);
                stringRedisTemplate.opsForSet().add(PostRedisKeys.topicDirty(), tid);
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
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_PERSISTENCE_POST, msg);
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
                    stringRedisTemplate.opsForHash().increment(PostRedisKeys.topic(Long.parseLong(tid)), "posts", -1);
                    stringRedisTemplate.opsForSet().add(PostRedisKeys.topicDirty(), tid);
                }
            }
        }
        stringRedisTemplate.delete(PostRedisKeys.postTopicIds(postId));
        cacheClient.delete(SystemRedisKeys.cachePostContent(postId));
        // 仅当原状态为公开时扣减作者发帖数
        if (oldStatus == 0) {
            stringRedisTemplate.opsForHash().increment(UserRedisKeys.stats(post.getUserId()), "posts", -1);
        }
        // ES 删除由 status -> 1 的 binlog 事件驱动。
    }

    // ========== 点赞 / 取消点赞 ==========

    @Override
    public boolean like(Long userId, Long postId, Long authorId) {
        // 在 Lua 执行前确保计数 key 存在，避免 HINCRBY 从 0 开始
        ensurePostCounts(postId);
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
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(), authorId.toString());
        stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(), postId.toString());
        return true;
    }

    @Override
    public boolean unlike(Long userId, Long postId, Long authorId) {
        // 在 Lua 执行前确保计数 key 存在，避免 HINCRBY 从 0 开始
        ensurePostCounts(postId);
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
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(), authorId.toString());
        stringRedisTemplate.opsForSet().add(PostRedisKeys.dirty(), postId.toString());
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

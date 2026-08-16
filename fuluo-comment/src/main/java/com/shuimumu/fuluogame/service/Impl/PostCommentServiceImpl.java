package com.shuimumu.fuluogame.service.Impl;


import cn.hutool.json.JSONUtil;

import com.shuimumu.fuluogame.client.UserProfileFetcher;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.dto.SyncMessage;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.utils.RedisIdWorker;
import com.shuimumu.fuluogame.dto.CommentPersistMsg;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.mapper.CommentMapper;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.service.PostCommentService;
import com.shuimumu.fuluogame.utils.CacheClient;
import com.shuimumu.fuluogame.utils.RedisUtils;
import com.shuimumu.fuluogame.utils.UserStatsRedisHelper;
import com.shuimumu.fuluogame.utils.RedisHashCounter;

import com.shuimumu.fuluogame.vo.CommentVO;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PostCommentServiceImpl implements PostCommentService {

    private static final DefaultRedisScript<Long> COMMENT_LIKE_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> COMMENT_UNLIKE_SCRIPT = new DefaultRedisScript<>();

    static {
        COMMENT_LIKE_SCRIPT.setLocation(new ClassPathResource("lua/comment_like.lua"));
        COMMENT_LIKE_SCRIPT.setResultType(Long.class);
        COMMENT_UNLIKE_SCRIPT.setLocation(new ClassPathResource("lua/comment_unlike.lua"));
        COMMENT_UNLIKE_SCRIPT.setResultType(Long.class);
    }

        /**
     * 确保 comment:counts:{commentId} 的 likes 字段存在。
     * 使用 HSETNX 写入，避免并发点赞 HINCRBY 后被陈旧 DB 底数覆盖。
     * 仅播种时设置 TTL；字段已存在不续期，点赞变更时再刷新。
     */
    private void ensureCommentCounts(Long commentId) {
        String key = PostRedisKeys.commentCounts(commentId);
        if (Boolean.TRUE.equals(stringRedisTemplate.opsForHash().hasKey(key, "likes"))) return;
        Comment c = commentMapper.selectById(commentId);
        if (c == null) return;
        RedisHashCounter.initCommentCounts(
                stringRedisTemplate,
                key,
                c.getLikes() != null ? c.getLikes() : 0);
        RedisHashCounter.refreshTtl(stringRedisTemplate, key,
                PostRedisKeys.COMMENT_COUNTS_TTL, TimeUnit.DAYS);
    }

    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private UserProfileFetcher userService;

    @Autowired
    private PostMapper postMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private CacheClient cacheClient;

    @Autowired
    private UserStatsRedisHelper userStatsRedisHelper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Override
    public void create(Long userId, Long postId, String content, Long parentId) {
        long pid = parentId != null && parentId > 0 ? parentId : 0L;
        long rootId = 0L;
        if (pid != 0) {
            String json = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cacheCommentContent(pid));
            Comment parent = null;
            if (json != null && !json.isEmpty()) {
                parent = JSONUtil.toBean(json, Comment.class);
            } else {
                parent = commentMapper.selectById(pid);
                if (parent != null) {
                    stringRedisTemplate.opsForValue().set(SystemRedisKeys.cacheCommentContent(pid),
                            JSONUtil.toJsonStr(parent), PostRedisKeys.COMMENT_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
                }
            }
            rootId = (parent == null) ? 0L
                    : (parent.getRootId() != null && parent.getRootId() > 0) ? parent.getRootId() : pid;
        }
        Long commentId = redisIdWorker.nextId("comment");
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_PERSISTENCE_COMMENT,
                new CommentPersistMsg(commentId, userId, postId, content, pid, rootId));
    }
    @Override
    public PageVO<CommentVO> list(Long postId, int page, int size) {
        String zsetKey = PostRedisKeys.commentZset(postId);
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().range(zsetKey, start, end);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0L, 1, 0);
        }
        List<Long> ids = members.stream().map(Long::parseLong).collect(Collectors.toList());
        String postJson = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cachePostContent(postId));
        Long postAuthorId = null;
        if (postJson != null && !postJson.isEmpty()) {
            postAuthorId = JSONUtil.parseObj(postJson).getLong("userId");
        } else {
            Post post = postMapper.selectById(postId);
            postAuthorId = post != null ? post.getUserId() : null;
        }
        // P2 修复：抽取公共组装（MGET+回填）
        Map<Long, CommentVO> resultMap = loadCommentVOs(ids, postAuthorId);
        List<Object> replyCounts = stringRedisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (Long cid : ids) {
                        if (resultMap.containsKey(cid)) {
                            connection.zCard(PostRedisKeys.replyZset(cid).getBytes());
                        }
                    }
                    return null;
                });
        int idx = 0;
        for (Long cid : ids) {
            CommentVO vo = resultMap.get(cid);
            if (vo != null && idx < replyCounts.size()) {
                vo.setReplyCount((Long) replyCounts.get(idx++));
            }
        }

        Set<Long> userIds = resultMap.values().stream()
                .map(CommentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, UserVO> userMap = userService.batchGetProfiles(userIds);
        for (CommentVO vo : resultMap.values()) {
            UserVO u = userMap.get(vo.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setAvatar(u.getAvatar());
            }
            vo.setAuthor(postAuthorId != null && postAuthorId.equals(vo.getUserId()));
        }
        String postTitle = getPostTitleFromCache(postId);
        for (CommentVO vo : resultMap.values()) {
            vo.setPostTitle(postTitle);
        }
        List<CommentVO> records = new ArrayList<>();
        for (Long cid : ids) {
            CommentVO vo = resultMap.get(cid);
            if (vo != null) records.add(vo);
        }
        Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
        fillCommentLikesCount(records);
        fillCommentLikedStatus(records);
        return PageVO.of(records, total != null ? total : 0L, page, size);
    }


    @Override
    public PageVO<CommentVO> replies(Long rootId, int page, int size) {
        String zsetKey = PostRedisKeys.replyZset(rootId);
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().range(zsetKey, start, end);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0L, page, size);
        }
        List<Long> ids = members.stream().map(Long::parseLong).collect(Collectors.toList());
        // P2 修复：抽取公共组装（MGET+回填），回复列表不标记作者身份
        Map<Long, CommentVO> resultMap = loadCommentVOs(ids, null);
        Set<Long> userIds = resultMap.values().stream()
                .map(CommentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, UserVO> userMap = userService.batchGetProfiles(userIds);
        for (CommentVO vo : resultMap.values()) {
            UserVO u = userMap.get(vo.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setAvatar(u.getAvatar());
            }
        }
        for (CommentVO vo : resultMap.values()) {
            vo.setPostTitle(getPostTitleFromCache(vo.getPostId()));
        }
        List<CommentVO> records = new ArrayList<>();
        for (Long cid : ids) {
            CommentVO vo = resultMap.get(cid);
            if (vo != null) records.add(vo);
        }
        Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
        fillCommentLikesCount(records);
        fillCommentLikedStatus(records);
        return PageVO.of(records, total != null ? total : 0L, page, size);
    }

    @Override
    public PageVO<CommentVO> list(Long postId, int page, int size, String sort) {
        if (!"hot".equals(sort)) {
            return list(postId, page, size);
        }
        String zsetKey = PostRedisKeys.commentHotZset(postId);
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0L, page, size);
        }
        List<Long> ids = members.stream().map(Long::parseLong).collect(Collectors.toList());
        String postJson = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cachePostContent(postId));
        Long postAuthorId = null;
        if (postJson != null && !postJson.isEmpty()) {
            postAuthorId = JSONUtil.parseObj(postJson).getLong("userId");
        } else {
            Post post = postMapper.selectById(postId);
            postAuthorId = post != null ? post.getUserId() : null;
        }
        // P2 修复：抽取公共组装（MGET+回填）
        Map<Long, CommentVO> resultMap = loadCommentVOs(ids, postAuthorId);
        List<Object> replyCounts = stringRedisTemplate.executePipelined(
                (org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
                    for (Long cid : ids) {
                        if (resultMap.containsKey(cid)) {
                            connection.zCard(PostRedisKeys.replyZset(cid).getBytes());
                        }
                    }
                    return null;
                });
        int idx = 0;
        for (Long cid : ids) {
            CommentVO vo = resultMap.get(cid);
            if (vo != null && idx < replyCounts.size()) {
                vo.setReplyCount((Long) replyCounts.get(idx++));
            }
        }
        Set<Long> userIds = resultMap.values().stream()
                .map(CommentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, UserVO> userMap = userService.batchGetProfiles(userIds);
        for (CommentVO vo : resultMap.values()) {
            UserVO u = userMap.get(vo.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setAvatar(u.getAvatar());
            }
            vo.setAuthor(postAuthorId != null && postAuthorId.equals(vo.getUserId()));
        }
        String postTitle = getPostTitleFromCache(postId);
        for (CommentVO vo : resultMap.values()) {
            vo.setPostTitle(postTitle);
        }
        List<CommentVO> records = new ArrayList<>();
        for (Long cid : ids) {
            CommentVO vo = resultMap.get(cid);
            if (vo != null) records.add(vo);
        }
        Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
        fillCommentLikesCount(records);
        fillCommentLikedStatus(records);
        return PageVO.of(records, total != null ? total : 0L, page, size);
    }

    @Override
    public boolean like(Long commentId, Long userId) {
        Long[] info = getCommentInfo(commentId);
        if (info == null) return false;
        Long authorId = info[0], postId = info[1];
        Long statsAuthorId = authorId != null ? authorId : 0L;
        ensureCommentCounts(commentId);
        // Lua 会 HINCRBY 作者 likes，先 ensure user:stats
        if (authorId != null) {
            userStatsRedisHelper.ensureUserStats(authorId);
        }
        List<String> keys = List.of(
                PostRedisKeys.commentLikes(commentId),
                UserRedisKeys.stats(statsAuthorId),
                PostRedisKeys.commentHotZset(postId),
                PostRedisKeys.commentCounts(commentId)
        );
        Long result = stringRedisTemplate.execute(COMMENT_LIKE_SCRIPT, keys,
                userId.toString(), commentId.toString());
        if (result == null || result == 0) return false;
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_COMMENT_LIKE,
                new SyncMessage("like", userId, commentId, authorId));
        if (authorId != null) {
            stringRedisTemplate.expire(UserRedisKeys.stats(authorId),
                    UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
            stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(authorId), authorId.toString());
        }
                // 评论点赞变更：刷新 comment:counts TTL
        RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.commentCounts(commentId),
                PostRedisKeys.COMMENT_COUNTS_TTL, TimeUnit.DAYS);
return true;
    }

    @Override
    public boolean unlike(Long commentId, Long userId) {
        Long[] info = getCommentInfo(commentId);
        if (info == null) return false;
        Long authorId = info[0], postId = info[1];
        Long statsAuthorId = authorId != null ? authorId : 0L;
        ensureCommentCounts(commentId);
        // Lua 会 HINCRBY 作者 likes，先 ensure user:stats
        if (authorId != null) {
            userStatsRedisHelper.ensureUserStats(authorId);
        }
        List<String> keys = List.of(
                PostRedisKeys.commentLikes(commentId),
                UserRedisKeys.stats(statsAuthorId),
                PostRedisKeys.commentHotZset(postId),
                PostRedisKeys.commentCounts(commentId)
        );
        Long result = stringRedisTemplate.execute(COMMENT_UNLIKE_SCRIPT, keys,
                userId.toString(), commentId.toString());
        if (result == null || result == 0) return false;
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_COMMENT_LIKE,
                new SyncMessage("unlike", userId, commentId, authorId));
        if (authorId != null) {
            stringRedisTemplate.expire(UserRedisKeys.stats(authorId),
                    UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
            stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(authorId), authorId.toString());
        }
                // 评论取消点赞：刷新 comment:counts TTL
        RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.commentCounts(commentId),
                PostRedisKeys.COMMENT_COUNTS_TTL, TimeUnit.DAYS);
return true;
    }

    private Long[] getCommentInfo(Long commentId) {
        String json = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cacheCommentContent(commentId));
        if (json != null && !json.isEmpty()) {
            return new Long[] {
                    JSONUtil.parseObj(json).getLong("userId"),
                    JSONUtil.parseObj(json).getLong("postId") };
        }
        Comment comment = commentMapper.selectById(commentId);
        if (comment != null) {
            return new Long[] { comment.getUserId(), comment.getPostId() };
        }
        return null;
    }

    /**
     * P1 优化：批量填充评论点赞数。
     * 原实现每条评论一次 HGET(comment:counts:{id} likes)，读一页（20 条）即 20 次 Redis 往返；
     * 这里用 executePipelined 把整页 HGET 合并为一次往返（与帖子侧 PostAssembler.fillCounts 对齐）。
     */
    private void fillCommentLikesCount(List<CommentVO> records) {
        if (records == null || records.isEmpty()) return;
        List<Object> results = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (CommentVO vo : records) {
                        byte[] key = PostRedisKeys.commentCounts(vo.getId()).getBytes(StandardCharsets.UTF_8);
                        connection.hashCommands().hGet(key, "likes".getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
        for (int i = 0; i < records.size() && i < results.size(); i++) {
            CommentVO vo = records.get(i);
            Object likes = results.get(i);
            if (likes != null) {
                vo.setLikes((int) RedisUtils.toLong(bytesToStr(likes)));
            }
        }
    }

    /**
     * P1 优化：批量填充当前用户评论点赞状态。
     * 原实现每条评论一次 SISMEMBER(comment:likes:{id})，20 条即 20 次往返；
     * 这里用 executePipelined 合并为一次往返（与帖子侧 PostAssembler.fillStatus 对齐）。
     */
    private void fillCommentLikedStatus(List<CommentVO> records) {
        if (records == null || records.isEmpty()) return;
        Long currentUserId;
        try { currentUserId = UserContext.getUser().getId(); } catch (Exception ignored) { return; }
        if (currentUserId == null) return;
        String uidStr = currentUserId.toString();
        List<Object> results = stringRedisTemplate.executePipelined(
                (RedisCallback<Object>) connection -> {
                    for (CommentVO vo : records) {
                        connection.setCommands().sIsMember(
                                PostRedisKeys.commentLikes(vo.getId()).getBytes(StandardCharsets.UTF_8),
                                uidStr.getBytes(StandardCharsets.UTF_8));
                    }
                    return null;
                });
        for (int i = 0; i < records.size() && i < results.size(); i++) {
            CommentVO vo = records.get(i);
            vo.setLiked(Boolean.TRUE.equals(results.get(i)));
        }
    }

    /** pipeline 返回的 byte[] 兜底转 String，与帖子侧 PostAssembler 保持一致。 */
    private static String bytesToStr(Object o) {
        if (o == null) return null;
        if (o instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return o.toString();
    }

    private CommentVO commentToVO(Comment c, Long postAuthorId) {
        CommentVO vo = new CommentVO();
        BeanUtils.copyProperties(c, vo);
        vo.setAuthor(postAuthorId != null && postAuthorId.equals(c.getUserId()));
        return vo;
    }

    /**
     * P2 修复（样板复制抽取）：按 id 保序加载评论内容缓存 + 互斥锁回填。
     * list/replies/list-hot 三处复制的「MGET + 命中/未命中 + 回填」统一到这里；
     * 命中后不再逐条续期（固定 TTL），回填只保留 status=0 的可见评论。
     */
    private Map<Long, CommentVO> loadCommentVOs(List<Long> ids, Long postAuthorId) {
        Map<Long, CommentVO> resultMap = new LinkedHashMap<>();
        List<String> cacheKeys = ids.stream()
                .map(SystemRedisKeys::cacheCommentContent).collect(Collectors.toList());
        List<String> cachedJsons = stringRedisTemplate.opsForValue().multiGet(cacheKeys);
        List<Long> missIds = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            String json = cachedJsons != null ? cachedJsons.get(i) : null;
            if (json != null && !json.isEmpty()) {
                resultMap.put(ids.get(i), JSONUtil.toBean(json, CommentVO.class));
            } else {
                missIds.add(ids.get(i));
            }
        }
        for (Long cid : missIds) {
            String dataKey = SystemRedisKeys.cacheCommentContent(cid);
            CommentVO vo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, CommentVO.class, () -> {
                Comment c = commentMapper.selectById(cid);
                if (c == null || c.getStatus() != 0) return null;
                return commentToVO(c, postAuthorId);
            }, PostRedisKeys.COMMENT_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            if (vo != null) resultMap.put(cid, vo);
        }
        return resultMap;
    }

    private String getPostTitleFromCache(Long postId) {
        String json = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cachePostContent(postId));
        if (json != null && !json.isEmpty()) {
            return JSONUtil.parseObj(json).getStr("title");
        }
        Post p = postMapper.selectById(postId);
        return p != null ? p.getTitle() : "";
    }
}

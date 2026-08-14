package com.shuimumu.fuluogame.utils;

import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.vo.PostContentVO;
import com.shuimumu.fuluogame.vo.UserVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class PostAssembler {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public PostContentVO toVO(Post post) {
        PostContentVO vo = new PostContentVO();
        vo.setId(post.getId());
        vo.setUserId(post.getUserId());
        vo.setTitle(post.getTitle());
        vo.setContent(post.getContent());
        vo.setCreateTime(post.getCreateTime());
        vo.setStatus(post.getStatus());
        vo.setModerationDesc(post.getModerationDesc());
        vo.setModerationWords(post.getModerationWords());
        if (post.getImages() != null && !post.getImages().isEmpty()) {
            try {
                vo.setImages(JSONUtil.toList(post.getImages(), String.class));
            } catch (Exception e) {
                vo.setImages(new ArrayList<>());
            }
        } else {
            vo.setImages(new ArrayList<>());
        }
        if (post.getTopicNames() != null && !post.getTopicNames().isEmpty()) {
            try {
                vo.setTopics(JSONUtil.toList(post.getTopicNames(), String.class));
            } catch (Exception e) {
                vo.setTopics(new ArrayList<>());
            }
        } else {
            vo.setTopics(new ArrayList<>());
        }
        return vo;
    }

    public void fillUsers(List<PostContentVO> records, Map<Long, UserVO> userMap) {
        if (records == null || records.isEmpty()) return;
        for (PostContentVO vo : records) {
            UserVO u = userMap.get(vo.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setAvatar(u.getAvatar());
            }
        }
    }

    /**
     * P1 修复：批量填充帖子计数。
     * 原实现每条帖子一次 HGETALL，读 feed（10 条）即产生 N 次 Redis 往返；
     * 这里改用 executePipelined，把整页的 likes/comments/views 三个字段一次往返取回。
     */
    public void fillCounts(List<PostContentVO> records) {
        if (records == null || records.isEmpty()) return;
        List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (PostContentVO vo : records) {
                byte[] key = PostRedisKeys.counts(vo.getId()).getBytes(StandardCharsets.UTF_8);
                connection.hashCommands().hGet(key, "likes".getBytes(StandardCharsets.UTF_8));
                connection.hashCommands().hGet(key, "comments".getBytes(StandardCharsets.UTF_8));
                connection.hashCommands().hGet(key, "views".getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        for (int i = 0; i < records.size(); i++) {
            PostContentVO vo = records.get(i);
            int base = i * 3;
            vo.setLikes((int) RedisUtils.toLong(bytesToStr(results.get(base))));
            vo.setComments((int) RedisUtils.toLong(bytesToStr(results.get(base + 1))));
            vo.setViews((int) RedisUtils.toLong(bytesToStr(results.get(base + 2))));
        }
    }

    /**
     * P1 修复：批量填充点赞/关注状态。
     * 原实现每帖一次 SISMEMBER(liked) + 每作者一次 SISMEMBER(following)，同为 N+1；
     * 这里用 executePipelined 把全部 SISMEMBER 合并为一次往返。
     */
    public void fillStatus(List<PostContentVO> records) {
        Long currentUserId;
        try {
            currentUserId = UserContext.getUser() != null ? UserContext.getUser().getId() : null;
        } catch (Exception ignored) {
            return;
        }
        if (currentUserId == null) return;
        String uidStr = currentUserId.toString();

        // 注意：authorIds 用同一个 Set 实例在 pipeline 与结果读取两处遍历，顺序一致，保证结果对号入座
        Set<Long> authorIds = records.stream()
                .map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());

        List<Object> results = stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long authorId : authorIds) {
                connection.setCommands().sIsMember(
                        UserRedisKeys.following(currentUserId).getBytes(StandardCharsets.UTF_8),
                        authorId.toString().getBytes(StandardCharsets.UTF_8));
            }
            for (PostContentVO vo : records) {
                connection.setCommands().sIsMember(
                        PostRedisKeys.likes(vo.getId()).getBytes(StandardCharsets.UTF_8),
                        uidStr.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        int idx = 0;
        Map<Long, Boolean> followingMap = new HashMap<>();
        for (Long authorId : authorIds) {
            followingMap.put(authorId, Boolean.TRUE.equals(results.get(idx++)));
        }
        for (PostContentVO vo : records) {
            vo.setLiked(Boolean.TRUE.equals(results.get(idx++)));
            vo.setFollowing(followingMap.getOrDefault(vo.getUserId(), false));
        }
    }

    /** pipeline 返回的 byte[] 兜底转 String，避免 StringRedisTemplate 反序列化差异导致计数被解析成 0 */
    private static String bytesToStr(Object o) {
        if (o == null) return null;
        if (o instanceof byte[] b) return new String(b, StandardCharsets.UTF_8);
        return o.toString();
    }
}

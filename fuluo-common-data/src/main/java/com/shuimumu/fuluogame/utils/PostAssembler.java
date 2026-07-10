package com.shuimumu.fuluogame.utils;

import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.vo.PostContentVO;
import com.shuimumu.fuluogame.vo.UserVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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

    public void fillCounts(List<PostContentVO> records) {
        for (PostContentVO vo : records) {
            Map<Object, Object> entries = stringRedisTemplate.opsForHash()
                    .entries(PostRedisKeys.counts(vo.getId()));
            if (!entries.isEmpty()) {
                vo.setLikes((int) RedisUtils.toLong(entries.get("likes")));
                vo.setComments((int) RedisUtils.toLong(entries.get("comments")));
                vo.setViews((int) RedisUtils.toLong(entries.get("views")));
            }
        }
    }

    public void fillStatus(List<PostContentVO> records) {
        Long currentUserId;
        try { currentUserId = UserContext.getUser().getId(); } catch (Exception ignored) { return; }
        String uidStr = currentUserId.toString();
        Set<Long> authorIds = records.stream()
                .map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Boolean> followingMap = new HashMap<>();
        for (Long authorId : authorIds) {
            followingMap.put(authorId, Boolean.TRUE.equals(
                    stringRedisTemplate.opsForSet().isMember(UserRedisKeys.following(currentUserId), authorId.toString())));
        }
        for (PostContentVO vo : records) {
            vo.setLiked(Boolean.TRUE.equals(
                    stringRedisTemplate.opsForSet().isMember(PostRedisKeys.likes(vo.getId()), uidStr)));
            vo.setFollowing(followingMap.getOrDefault(vo.getUserId(), false));
        }
    }
}

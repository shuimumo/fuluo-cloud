package com.shuimumu.fuluogame.service.Impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.mapper.PostTopicMapper;
import com.shuimumu.fuluogame.mapper.TopicMapper;
import com.shuimumu.fuluogame.service.TopicService;
import com.shuimumu.fuluogame.client.UserProfileFetcher;
import com.shuimumu.fuluogame.utils.CacheClient;
import com.shuimumu.fuluogame.utils.LocalCache;
import com.shuimumu.fuluogame.utils.PostAssembler;
import com.shuimumu.fuluogame.utils.PostFeedSupport;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;
import com.shuimumu.fuluogame.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TopicServiceImpl implements TopicService {

    @Autowired
    private TopicMapper topicMapper;

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
    private LocalCache localCache;

    private static final String TOPIC_LIST_CACHE = "cache:topicList";

    @Override
    public List<Topic> list() {
        List<Topic> local = localCache.get(TOPIC_LIST_CACHE);
        if (local != null) return local;

        String json = stringRedisTemplate.opsForValue().get(TOPIC_LIST_CACHE);
        if (json != null && !json.isEmpty()) {
            local = JSONUtil.toList(json, Topic.class);
            localCache.put(TOPIC_LIST_CACHE, local);
            return local;
        }
        List<Topic> topics = topicMapper.selectList(
                new LambdaQueryWrapper<Topic>().orderByDesc(Topic::getTotalViews));
        stringRedisTemplate.opsForValue().set(TOPIC_LIST_CACHE, JSONUtil.toJsonStr(topics));
        localCache.put(TOPIC_LIST_CACHE, topics);
        return topics;
    }

    @Override
    public PageVO<PostContentVO> getPosts(Long topicId, String tab, int page, int size) {
        if (page < 1) page = 1;
        if (size < 1) size = 10;
        long start = (long) (page - 1) * size;
        long end = start + size - 1;

        // 话题热门：仅 ZSET
        if ("hot".equals(tab)) {
            String zsetKey = PostRedisKeys.topicPostsHotZset(topicId);
            Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
            if (members == null || members.isEmpty()) {
                return PageVO.of(List.of(), 0, page, size);
            }
            List<PostContentVO> records = postFeedSupport.loadAndAssemble(postFeedSupport.membersToIds(members));
            Long total = stringRedisTemplate.opsForZSet().zCard(zsetKey);
            return PageVO.of(records, total != null ? total : 0, page, size);
        }

        // 话题最新：窗口内 Redis，之外/空窗 MySQL
        String latestKey = PostRedisKeys.topicPostsLatestZset(topicId);
        Long zcard = stringRedisTemplate.opsForZSet().zCard(latestKey);
        long window = zcard != null ? zcard : 0L;
        List<Long> postIds;
        if (window > 0 && start < window) {
            Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(latestKey, start, end);
            postIds = postFeedSupport.membersToIds(members);
        } else {
            postIds = postMapper.selectLatestIdsByTopic(topicId, start, size);
            if (postIds == null) postIds = List.of();
        }
        List<PostContentVO> records = postFeedSupport.loadAndAssemble(postIds);
        long total = getTopicPublishedCount(topicId);
        return PageVO.of(records, total, page, size);
    }

    /**
     * P2 修复：话题已发布帖子总数走 Redis 短缓存，避免话题最新流每请求 COUNT(*)。
     * 总数允许 60s 延迟，缓存到期后重新 COUNT 回填。
     */
    private long getTopicPublishedCount(Long topicId) {
        String key = PostRedisKeys.countPublishedByTopic(topicId);
        String cached = stringRedisTemplate.opsForValue().get(key);
        if (cached != null) {
            try { return Long.parseLong(cached); } catch (NumberFormatException ignored) { }
        }
        long total = postMapper.countPublishedByTopic(topicId);
        stringRedisTemplate.opsForValue().set(key, String.valueOf(total),
                PostRedisKeys.COUNT_CACHE_TTL_SECONDS, TimeUnit.SECONDS);
        return total;
    }
}

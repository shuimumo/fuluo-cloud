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
        String zsetKey = "hot".equals(tab)
                ? PostRedisKeys.topicPostsHotZset(topicId)
                : PostRedisKeys.topicPostsLatestZset(topicId);
        long start = (long) (page - 1) * size;
        long end = start + size - 1;
        Set<String> members = stringRedisTemplate.opsForZSet().reverseRange(zsetKey, start, end);
        if (members == null || members.isEmpty()) {
            return PageVO.of(List.of(), 0, page, size);
        }
        List<Long> postIds = members.stream().map(Long::parseLong).collect(Collectors.toList());
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
        for (Long pid : missIds) {
            String dataKey = SystemRedisKeys.cachePostContent(pid);
            PostContentVO vo = cacheClient.queryWithMutex("lock:" + dataKey, dataKey, PostContentVO.class, () -> {
                Post p = postMapper.selectById(pid);
                if (p == null || p.getStatus() != 0) return null;
                return postAssembler.toVO(p);
            }, PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
            if (vo != null) resultMap.put(pid, vo);
        }
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
}

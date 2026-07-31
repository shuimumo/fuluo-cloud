package com.shuimumu.fuluogame.utils;

import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.client.UserProfileFetcher;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.vo.PostContentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 首页/话题 Feed 公共组装：按 id 列表保序加载内容缓存、补用户/计数/状态。
 */
@Component
public class PostFeedSupport {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private PostAssembler postAssembler;
    @Autowired
    private UserProfileFetcher userProfileFetcher;

    public List<PostContentVO> loadAndAssemble(List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return List.of();
        }
        List<String> cacheKeys = postIds.stream()
                .map(SystemRedisKeys::cachePostContent)
                .collect(Collectors.toList());
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
            if (vo != null) {
                resultMap.put(pid, vo);
            }
        }
        List<PostContentVO> records = new ArrayList<>();
        for (Long pid : postIds) {
            PostContentVO vo = resultMap.get(pid);
            if (vo != null) {
                records.add(vo);
            }
        }
        postAssembler.fillUsers(records, userProfileFetcher.batchGetProfiles(
                records.stream().map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet())));
        postAssembler.fillCounts(records);
        postAssembler.fillStatus(records);
        return records;
    }

    public List<Long> membersToIds(Set<String> members) {
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream().map(Long::parseLong).collect(Collectors.toList());
    }
}

package com.shuimumu.fuluogame.service.Impl;

import cn.hutool.json.JSONUtil;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.shuimumu.fuluogame.client.UserProfileFetcher;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.service.SearchService;
import com.shuimumu.fuluogame.utils.CacheClient;
import com.shuimumu.fuluogame.utils.PostAssembler;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 帖子搜索读路径（从单体 {@code PostServiceImpl.searchPosts} 迁入）：
 * 查 ES 命中 ID → MGET 帖子内容缓存（缺失回源 DB 并回填）→ 填高亮 → 经
 * {@link UserProfileFetcher}（{@code UserApi} Feign）填作者、填计数/状态。
 */
@Slf4j
@Service
public class SearchServiceImpl implements SearchService {

    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private PostMapper postMapper;
    @Autowired
    private CacheClient cacheClient;
    @Autowired
    private PostAssembler postAssembler;
    @Autowired
    private UserProfileFetcher userProfileFetcher;

    @Override
    public PageVO<PostContentVO> searchPosts(String q, int page, int size) {
        try {
            var response = esClient.search(s -> s.index("posts")
                    .query(qr -> qr.multiMatch(mm -> mm.query(q).fields("title", "content")))
                    .highlight(h -> h.fields("title", f -> f.preTags("<em>").postTags("</em>"))
                            .fields("content", f -> f.preTags("<em>").postTags("</em>")))
                    .from((page - 1) * size).size(size),
                    Map.class);
            List<Long> postIds = new ArrayList<>();
            Map<Long, Map<String, String>> highlights = new HashMap<>();
            if (response.hits().hits() != null) {
                for (var hit : response.hits().hits()) {
                    Long pid = Long.parseLong(hit.id());
                    postIds.add(pid);
                    if (hit.highlight() != null) {
                        Map<String, String> hl = new HashMap<>();
                        if (hit.highlight().get("title") != null && !hit.highlight().get("title").isEmpty())
                            hl.put("title", String.join("", hit.highlight().get("title")));
                        if (hit.highlight().get("content") != null && !hit.highlight().get("content").isEmpty())
                            hl.put("content", String.join("", hit.highlight().get("content")));
                        highlights.put(pid, hl);
                    }
                }
            }
            long total = response.hits().total() != null ? response.hits().total().value() : 0;
            if (postIds.isEmpty()) return PageVO.of(List.of(), total, page, size);
            // MGET 缓存
            List<String> keys = postIds.stream().map(SystemRedisKeys::cachePostContent).collect(Collectors.toList());
            List<String> jsons = stringRedisTemplate.opsForValue().multiGet(keys);
            List<PostContentVO> records = new ArrayList<>();
            List<Long> miss = new ArrayList<>();
            for (int i = 0; i < postIds.size(); i++) {
                String json = jsons != null ? jsons.get(i) : null;
                if (json != null && !json.isEmpty()) {
                    records.add(JSONUtil.toBean(json, PostContentVO.class));
                } else {
                    miss.add(postIds.get(i));
                }
            }
            if (!miss.isEmpty()) {
                List<Post> missPosts = postMapper.selectByIds(miss);
                for (Post p : missPosts) {
                    if (p.getStatus() == 0) {
                        PostContentVO vo = postAssembler.toVO(p);
                        cacheClient.set(SystemRedisKeys.cachePostContent(vo.getId()),
                                JSONUtil.toJsonStr(vo), PostRedisKeys.POST_CONTENT_CACHE_TTL, TimeUnit.MINUTES);
                        records.add(vo);
                    }
                }
            }
            // 填高亮
            for (PostContentVO vo : records) {
                Map<String, String> hl = highlights.get(vo.getId());
                if (hl != null) {
                    if (hl.containsKey("title")) vo.setTitleHighlight(hl.get("title"));
                    if (hl.containsKey("content")) vo.setContentHighlight(hl.get("content"));
                }
            }
            postAssembler.fillUsers(records, userProfileFetcher.batchGetProfiles(
                    records.stream().map(PostContentVO::getUserId).filter(Objects::nonNull).collect(Collectors.toSet())));
            postAssembler.fillCounts(records);
            postAssembler.fillStatus(records);
            return PageVO.of(records, total, page, size);
        } catch (Exception e) {
            log.error("ES 搜索失败 q={}", q, e);
            return PageVO.of(List.of(), 0, page, size);
        }
    }
}

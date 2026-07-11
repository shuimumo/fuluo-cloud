package com.shuimumu.fuluogame.service.Impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.service.AiService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
public class AiServiceImpl implements AiService {

    @Autowired
    @Qualifier("SummariseClient")
    private ChatClient summariseClient;

    @Autowired
    @Qualifier("PolishClient")
    private ChatClient polishClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public String getPrompt(Long postId) {
        return buildPrompt(postId);
    }

    @Override
    public Flux<String> polish(String text) {
        return polishClient.prompt()
                .user(text)
                .stream()
                .content();
    }

    @Override
    public Flux<String> summarise(Long postId) {
        // 1. 先查缓存
        String cacheKey = SystemRedisKeys.cacheAiSummarise(postId);
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            return Flux.just(cached);
        }

        // 2. 构建prompt
        String prompt = buildPrompt(postId);
        if (prompt == null) {
            return Flux.just("帖子内容加载失败");
        }

        // 3. 调用AI并收集结果写入缓存
        StringBuilder fullResult = new StringBuilder();
        return summariseClient.prompt()
                .user(prompt)
                .stream()
                .content()
                .doOnNext(fullResult::append)
                .doOnComplete(() -> {
                    String result = fullResult.toString();
                    if (!result.isEmpty()) {
                        stringRedisTemplate.opsForValue()
                                .set(cacheKey, result, SystemRedisKeys.AI_SUMMARISE_TTL, TimeUnit.MINUTES);
                    }
                });
    }

    private String buildPrompt(Long postId) {
        // 1. 获取帖子内容
        String postJson = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cachePostContent(postId));
        if (postJson == null || postJson.isEmpty()) {
            return null;
        }
        JSONObject postObj = JSONUtil.parseObj(postJson);
        String title = postObj.getStr("title");
        String content = postObj.getStr("content");

        StringBuilder sb = new StringBuilder();
        sb.append("【帖子标题】：").append(title).append("\n");
        sb.append("【帖子内容】：").append(content).append("\n");

        // 2. 获取最热20条评论ID
        Set<String> topIds = stringRedisTemplate.opsForZSet()
                .reverseRange(PostRedisKeys.commentHotZset(postId), 0, 19);
        if (topIds == null || topIds.isEmpty()) {
            return sb.toString();
        }

        int idx = 1;
        // 3. 遍历评论 + 回复
        for (String id : topIds) {
            long cid = Long.parseLong(id);
            String commentJson = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cacheCommentContent(cid));
            if (commentJson == null || commentJson.isEmpty()) {
                continue;
            }
            JSONObject commentObj = JSONUtil.parseObj(commentJson);
            String commentContent = commentObj.getStr("content");
            if (commentContent == null || commentContent.isEmpty()) {
                continue;
            }

            sb.append("\n【评论】：").append(commentContent).append("\n");

            // 获取回复
            Set<String> replyIds = stringRedisTemplate.opsForZSet()
                    .range(PostRedisKeys.replyZset(cid), 0, 4);
            if (replyIds == null || replyIds.isEmpty()) {
                continue;
            }

            sb.append("【回复列表】：\n");
            List<String> rKeys = new ArrayList<>();
            for (String rid : replyIds) {
                rKeys.add(SystemRedisKeys.cacheCommentContent(Long.parseLong(rid)));
            }
            List<String> rJsons = stringRedisTemplate.opsForValue().multiGet(rKeys);
            if (rJsons == null) {
                continue;
            }
            for (String rj : rJsons) {
                if (rj == null || rj.isEmpty()) {
                    continue;
                }
                JSONObject replyObj = JSONUtil.parseObj(rj);
                String replyContent = replyObj.getStr("content");
                if (replyContent == null || replyContent.isEmpty()) {
                    continue;
                }
                sb.append("   ↳ ").append(replyContent).append("\n");
            }
        }

        return sb.toString();
    }
}

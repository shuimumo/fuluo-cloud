package com.shuimumu.fuluogame.mq;

import com.shuimumu.fuluogame.utils.RedisHashCounter;
import com.shuimumu.fuluogame.utils.UserStatsRedisHelper;

import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.dto.PostModerationMsg;
import com.shuimumu.fuluogame.dto.NotificationEvent;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.utils.ContentModerationUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 帖子审核消费者（批量）。
 * 复用帖子落库的批量监听容器：一次接收一批消息，批量查库 + 逐条审核 + 批量回写状态，
 * 把原来"每条 1 次 SELECT + 1 次 UPDATE"降为"每批 1 次 SELECT + 1 次 UPDATE"。
 * 阿里云文本审核接口为单条调用，真实审核模式下仍逐条调 API（受账户 QPS 配额限制）。
 */
@Slf4j
@Component
public class PostModerationConsumer {

    @Value("${fuluo.moderation.enabled:true}")
    private boolean moderationEnabled;


    @Autowired
    private PostMapper postMapper;

    @Autowired
    private ContentModerationUtil contentModerationUtil;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private UserStatsRedisHelper userStatsRedisHelper;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_MODERATION,
            containerFactory = "postBatchListenerContainerFactory")
    public void handlePost(List<PostModerationMsg> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        try {
            // 1) 批量查库，只处理待审核（status=2）
            List<Long> ids = messages.stream().map(PostModerationMsg::getPostId)
                    .distinct().collect(Collectors.toList());
            Map<Long, Post> postById = new HashMap<>();
            List<Post> posts = postMapper.selectBatchIds(ids);
            if (posts != null) {
                for (Post p : posts) {
                    postById.put(p.getId(), p);
                }
            }

            List<Post> passPosts = new ArrayList<>();
            List<Post> rejectPosts = new ArrayList<>();
            List<PostModerationMsg> passMsgs = new ArrayList<>();
            List<NotificationEvent> notifications = new ArrayList<>();

            // 2) 逐条审核（跳过模式直接 PASS；真实模式循环调阿里云单条接口）
            for (PostModerationMsg msg : messages) {
                Post post = postById.get(msg.getPostId());
                if (post == null || post.getStatus() != 2) continue;

                ContentModerationUtil.ModerationResult result;
                if (!moderationEnabled) {
                    // 压测/本地调试：跳过阿里云 Green 审核，直接放行
                    result = new ContentModerationUtil.ModerationResult(
                            true, ContentModerationUtil.Action.PASS, "none", null, null, null, 0);
                } else {
                    result = contentModerationUtil.moderateComment(post.getTitle() + " " + post.getContent());
                }

                ContentModerationUtil.Action action = result.getAction();
                if (result.getDescription() == null || result.getDescription().isBlank()) {
                    result.setDescription("内容未通过审核");
                }
                if (action == ContentModerationUtil.Action.RETRY) {
                    throw new RuntimeException("审核接口异常，触发MQ重试");
                }
                if (action == ContentModerationUtil.Action.PASS) {
                    post.setStatus(0); post.setModerationDesc(null); post.setModerationWords(null);
                    passPosts.add(post);
                    passMsgs.add(msg);
                } else {
                    if (action == ContentModerationUtil.Action.MANUAL_REVIEW) {
                        post.setStatus(2);
                        log.warn("帖子待人工审核 postId={} desc={}", post.getId(), result.getDescription());
                    } else {
                        post.setStatus(3);
                        log.warn("帖子审核拒绝 postId={} label={} desc={}", post.getId(),
                                result.getLabel(), result.getDescription());
                    }
                    post.setModerationDesc(result.getDescription());
                    post.setModerationWords(result.getRiskWords());
                    rejectPosts.add(post);
                    if (action != ContentModerationUtil.Action.MANUAL_REVIEW) {
                        notifications.add(NotificationEvent.system("moderation-post:" + post.getId(),
                                post.getUserId(), post.getId(), post.getId(),
                                "你的帖子未通过审核：" + result.getDescription()));
                    }
                }
            }

            // 3) 批量回写审核状态（一次 UPDATE）
            List<Post> toUpdate = new ArrayList<>(passPosts.size() + rejectPosts.size());
            toUpdate.addAll(passPosts);
            toUpdate.addAll(rejectPosts);
            if (!toUpdate.isEmpty()) {
                postMapper.updateStatusBatch(toUpdate);
            }

            // 4) 审核通过的新帖：作者统计 + 话题计数（binlog 无法推导的一次性语义）
            //    Redis 侧为 best-effort：失败仅记日志，不回滚已落库的状态（否则整批重投后 status!=2 全部跳过，
            //    反而放大丢失面）；状态由 status 2->0 的 binlog 事件 + dirty 标记驱动最终一致。
            try {
                for (PostModerationMsg msg : passMsgs) {
                    if (Boolean.TRUE.equals(msg.getIsNew())) {
                        incrAuthorStats(msg.getUserId());
                        if (msg.getTopicIds() != null && !msg.getTopicIds().isEmpty()) {
                            for (String tid : msg.getTopicIds().split(",")) {
                                Long topicId = Long.valueOf(tid.trim());
                                stringRedisTemplate.opsForHash().increment(PostRedisKeys.topic(topicId), "posts", 1);
                                RedisHashCounter.refreshTtl(stringRedisTemplate, PostRedisKeys.topic(topicId),
                                        PostRedisKeys.TOPIC_STATS_TTL, TimeUnit.DAYS);
                                stringRedisTemplate.opsForSet().add(PostRedisKeys.topicDirty(topicId), topicId.toString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.error("帖子批量审核 Redis 统计更新失败（DB 已更新，统计待补偿）", e);
            }

            // 5) 拒绝通知（best-effort，失败记日志即可，重投也会因 status 变更被跳过）
            try {
                for (NotificationEvent event : notifications) {
                    rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                            RabbitMQConfig.NOTIFICATION_KEY, event);
                }
            } catch (Exception e) {
                log.error("帖子批量审核通知发送失败（已记录，不重投）", e);
            }

            log.info("帖子批量审核完成 batchSize={} pass={} reject={}",
                    messages.size(), passPosts.size(), rejectPosts.size());
        } catch (Exception e) {
            log.error("帖子批量审核失败 batchSize={}", messages.size(), e);
            throw new RuntimeException("审核处理失败，触发MQ重试", e);
        }
    }

    private void incrAuthorStats(Long userId) {
        // 审核通过后 +posts，先 ensure 再 HINCRBY
        userStatsRedisHelper.ensureUserStats(userId);
        String key = UserRedisKeys.stats(userId);
        stringRedisTemplate.opsForHash().increment(key, "posts", 1);
        stringRedisTemplate.expire(key, UserRedisKeys.USER_STATS_TTL, TimeUnit.DAYS);
        stringRedisTemplate.opsForSet().add(UserRedisKeys.statsDirty(userId), userId.toString());
    }
}


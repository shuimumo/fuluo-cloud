package com.shuimumu.fuluogame.service.Impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shuimumu.fuluogame.constants.PostRedisKeys;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.dto.NotificationEvent;
import com.shuimumu.fuluogame.entity.Announcement;
import com.shuimumu.fuluogame.entity.Banner;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.mapper.AnnouncementMapper;
import com.shuimumu.fuluogame.mapper.BannerMapper;
import com.shuimumu.fuluogame.mapper.CommentMapper;
import com.shuimumu.fuluogame.mapper.PostMapper;
import com.shuimumu.fuluogame.mapper.TopicMapper;
import com.shuimumu.fuluogame.mq.ContentSyncPublisher;
import com.shuimumu.fuluogame.service.AdminService;
import com.shuimumu.fuluogame.vo.PageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class AdminServiceImpl implements AdminService {

    @Autowired private BannerMapper bannerMapper;
    @Autowired private AnnouncementMapper announcementMapper;
    @Autowired private PostMapper postMapper;
    @Autowired private CommentMapper commentMapper;
    @Autowired private TopicMapper topicMapper;
    @Autowired private StringRedisTemplate stringRedisTemplate;
    @Autowired private com.shuimumu.fuluogame.utils.LocalCache localCache;
    @Autowired private RabbitTemplate rabbitTemplate;
    // P2 修复（去 Canal）：admin 直写 DB 后直接扇出，不再依赖 binlog->Canal
    @Autowired private ContentSyncPublisher contentSyncPublisher;

    // ========== Banner ==========
    @Override
    public List<Banner> listBanners() {
        return bannerMapper.selectList(
                new LambdaQueryWrapper<Banner>().orderByAsc(Banner::getSort));
    }

    @Override
    public void saveBanner(Banner banner) {
        bannerMapper.insert(banner);
        refreshBannerCache();
    }

    @Override
    public void updateBanner(Banner banner) {
        bannerMapper.updateById(banner);
        refreshBannerCache();
    }

    @Override
    public void deleteBanner(Long id) {
        bannerMapper.deleteById(id);
        refreshBannerCache();
    }

    private void refreshBannerCache() {
        List<Banner> list = bannerMapper.selectList(
                new LambdaQueryWrapper<Banner>().eq(Banner::getStatus, 1).orderByAsc(Banner::getSort));
        stringRedisTemplate.opsForValue().set(SystemRedisKeys.cacheBanners(), JSONUtil.toJsonStr(list));
    }

    // ========== Announcement ==========
    @Override
    public List<Announcement> listAnnouncements() {
        return announcementMapper.selectList(
                new LambdaQueryWrapper<Announcement>().orderByDesc(Announcement::getCreateTime));
    }

    @Override
    public void saveAnnouncement(Announcement announcement) {
        announcementMapper.insert(announcement);
        refreshAnnouncementCache();
    }

    @Override
    public void updateAnnouncement(Announcement announcement) {
        announcementMapper.updateById(announcement);
        refreshAnnouncementCache();
    }

    @Override
    public void deleteAnnouncement(Long id) {
        announcementMapper.deleteById(id);
        refreshAnnouncementCache();
    }

    private void refreshAnnouncementCache() {
        List<Announcement> list = announcementMapper.selectList(
                new LambdaQueryWrapper<Announcement>().orderByDesc(Announcement::getCreateTime));
        stringRedisTemplate.opsForValue().set(SystemRedisKeys.cacheAnnouncements(), JSONUtil.toJsonStr(list));
    }

    // ========== Topic ==========
    @Override
    public void saveTopic(Topic topic) {
        topicMapper.insert(topic);
        localCache.broadcastInvalidate("cache:topicList");
        stringRedisTemplate.delete("cache:topicList");
    }

    @Override
    public void updateTopic(Topic topic) {
        topicMapper.updateById(topic);
        localCache.broadcastInvalidate("cache:topicList");
        stringRedisTemplate.delete("cache:topicList");
    }

    @Override
    public void deleteTopic(Long id) {
        topicMapper.deleteById(id);
        localCache.broadcastInvalidate("cache:topicList");
        stringRedisTemplate.delete("cache:topicList");
    }

    // ========== Review ==========
    @Override
    public PageVO<Post> listPosts(Integer status, int page, int size) {
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<>();
        if (status != null) wrapper.eq(Post::getStatus, status);
        wrapper.orderByDesc(Post::getCreateTime);
        Page<Post> result = postMapper.selectPage(new Page<>(page, size), wrapper);
        return PageVO.of(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public PageVO<Comment> listComments(Integer status, int page, int size) {
        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        if (status != null) wrapper.eq(Comment::getStatus, status);
        wrapper.orderByDesc(Comment::getCreateTime);
        Page<Comment> result = commentMapper.selectPage(new Page<>(page, size), wrapper);
        return PageVO.of(result.getRecords(), result.getTotal(), page, size);
    }

    @Override
    public void approvePost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null) return;
        post.setStatus(0);
        post.setModerationDesc(null);
        post.setModerationWords(null);
        postMapper.updateById(post);
        // P2 修复（去 Canal）：审核通过直接扇出缓存/ES/收件箱
        contentSyncPublisher.publishPostApproved(post.getId(), post.getTitle(),
                post.getContent(), post.getUserId(), post.getCreateTime());
    }

    @Override
    public void rejectPost(Long postId, String reason) {
        Post post = postMapper.selectById(postId);
        if (post == null) return;
        post.setStatus(3);
        post.setModerationDesc(reason);
        postMapper.updateById(post);
        rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_KEY,
                NotificationEvent.system("admin-post-reject:" + postId + ":" + reasonHash(reason),
                        post.getUserId(), postId, postId, "你的帖子未通过审核：" + reason));
        // 清除公开缓存 + 公开 ZSET（用户自己列表保留）
        stringRedisTemplate.delete(SystemRedisKeys.cachePostContent(postId));
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.latestZset(), postId.toString());
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.hotZset(), postId.toString());
    }


    @Override
    public void approveComment(Long commentId) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) return;
        comment.setStatus(0);
        comment.setModerationDesc(null);
        comment.setModerationWords(null);
        commentMapper.updateById(comment);
        // P2 修复（去 Canal）：审核通过直接扇出评论缓存+互动通知
        contentSyncPublisher.publishCommentApproved(comment.getId(), comment.getUserId(), comment.getPostId());
    }

    @Override
    public void rejectComment(Long commentId, String reason) {
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) return;
        comment.setStatus(3);
        comment.setModerationDesc(reason);
        commentMapper.updateById(comment);
        rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_EXCHANGE,
                RabbitMQConfig.NOTIFICATION_KEY,
                NotificationEvent.system("admin-comment-reject:" + commentId + ":" + reasonHash(reason),
                        comment.getUserId(), commentId, comment.getPostId(), "你的评论未通过审核：" + reason));
        // 清除评论缓存 + ZSET
        stringRedisTemplate.delete(SystemRedisKeys.cacheCommentContent(commentId));
        if (comment.getRootId() == null || comment.getRootId() == 0) {
            stringRedisTemplate.opsForZSet().remove(PostRedisKeys.commentZset(comment.getPostId()), commentId.toString());
        } else {
            stringRedisTemplate.opsForZSet().remove(PostRedisKeys.replyZset(comment.getRootId()), commentId.toString());
        }
        stringRedisTemplate.opsForZSet().remove(PostRedisKeys.userCommentZset(comment.getUserId()), commentId.toString());
    }

    private String reasonHash(String reason) {
        return Integer.toHexString(reason == null ? 0 : reason.hashCode());
    }

}

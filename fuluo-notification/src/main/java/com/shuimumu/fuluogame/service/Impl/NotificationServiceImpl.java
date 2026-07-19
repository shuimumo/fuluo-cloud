package com.shuimumu.fuluogame.service.Impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.shuimumu.fuluogame.entity.Notification;
import com.shuimumu.fuluogame.exception.BusinessException;
import com.shuimumu.fuluogame.mapper.NotificationMapper;
import com.shuimumu.fuluogame.service.NotificationService;
import com.shuimumu.fuluogame.vo.NotificationVO;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.UnreadSummaryVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationServiceImpl implements NotificationService {

    private static final List<String> COMMENT_TYPES = List.of("COMMENT", "REPLY");
    private static final List<String> LIKE_TYPES = List.of("POST_LIKE", "COMMENT_LIKE");

    private final NotificationMapper notificationMapper;

    public NotificationServiceImpl(NotificationMapper notificationMapper) {
        this.notificationMapper = notificationMapper;
    }

    @Override
    public PageVO<NotificationVO> list(Long userId, String category, int page, int size) {
        LambdaQueryWrapper<Notification> query = scopedQuery(userId, category)
                .orderByDesc(Notification::getCreateTime)
                .orderByDesc(Notification::getId);
        Page<Notification> result = notificationMapper.selectPage(new Page<>(page, size), query);
        List<NotificationVO> records = result.getRecords().stream()
                .map(NotificationVO::from)
                .toList();
        return PageVO.of(records, result.getTotal(), page, size);
    }

    @Override
    public UnreadSummaryVO unreadSummary(Long userId) {
        long comment = unreadCount(userId, COMMENT_TYPES);
        long like = unreadCount(userId, LIKE_TYPES);
        long follow = unreadCount(userId, List.of("FOLLOW"));
        long system = unreadCount(userId, List.of("SYSTEM"));
        return new UnreadSummaryVO(comment + like + follow + system,
                comment, like, follow, system);
    }

    @Override
    public boolean markRead(Long userId, Long notificationId) {
        LambdaUpdateWrapper<Notification> update = new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getId, notificationId)
                .eq(Notification::getRecipientId, userId)
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true)
                .set(Notification::getReadTime, LocalDateTime.now());
        return notificationMapper.update(null, update) > 0;
    }

    @Override
    public int markAllRead(Long userId, String category) {
        LambdaUpdateWrapper<Notification> update = new LambdaUpdateWrapper<Notification>()
                .eq(Notification::getRecipientId, userId)
                .eq(Notification::getIsRead, false)
                .set(Notification::getIsRead, true)
                .set(Notification::getReadTime, LocalDateTime.now());
        if (!"all".equals(category)) {
            update.in(Notification::getType, typesFor(category));
        }
        return notificationMapper.update(null, update);
    }

    private long unreadCount(Long userId, List<String> types) {
        Long count = notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, userId)
                .eq(Notification::getIsRead, false)
                .in(Notification::getType, types));
        return count != null ? count : 0L;
    }

    private LambdaQueryWrapper<Notification> scopedQuery(Long userId, String category) {
        LambdaQueryWrapper<Notification> query = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getRecipientId, userId);
        if (!"all".equals(category)) {
            query.in(Notification::getType, typesFor(category));
        }
        return query;
    }

    private List<String> typesFor(String category) {
        return switch (category) {
            case "comment" -> COMMENT_TYPES;
            case "like" -> LIKE_TYPES;
            case "follow" -> List.of("FOLLOW");
            case "system" -> List.of("SYSTEM");
            default -> throw new BusinessException("不支持的通知分类");
        };
    }
}

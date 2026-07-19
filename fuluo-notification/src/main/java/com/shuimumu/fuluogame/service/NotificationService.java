package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.vo.NotificationVO;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.UnreadSummaryVO;

public interface NotificationService {
    PageVO<NotificationVO> list(Long userId, String category, int page, int size);

    UnreadSummaryVO unreadSummary(Long userId);

    boolean markRead(Long userId, Long notificationId);

    int markAllRead(Long userId, String category);
}

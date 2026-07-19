package com.shuimumu.fuluogame.vo;

import com.shuimumu.fuluogame.entity.Notification;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;

@Data
public class NotificationVO {
    private Long id;
    private Long actorId;
    private String type;
    private String targetType;
    private Long targetId;
    private Long postId;
    private String actorName;
    private String actorAvatar;
    private String targetTitle;
    private String content;
    private Boolean isRead;
    private LocalDateTime readTime;
    private LocalDateTime createTime;

    public static NotificationVO from(Notification notification) {
        NotificationVO vo = new NotificationVO();
        BeanUtils.copyProperties(notification, vo);
        return vo;
    }
}

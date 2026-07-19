package com.shuimumu.fuluogame.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String eventId;
    private Long recipientId;
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
}

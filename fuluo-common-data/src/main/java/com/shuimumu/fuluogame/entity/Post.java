package com.shuimumu.fuluogame.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("post")
public class Post {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 帖子ID

    private Long userId;                // 作者ID

    private String title;               // 帖子标题

    private String content;             // 帖子内容

    private String images;              // 图片URL（JSON数组格式）

    private Integer likes;              // 点赞数

    private Integer comments;           // 评论数

    private Integer views;              // 浏览数

    private Integer shares;             // 分享数

    private String topicNames;          // 话题名称JSON数组

    private Integer status;             // 状态

    private String moderationDesc;      // 审核拒绝原因

    private String moderationWords;     // 审核命中敏感词

    private LocalDateTime createTime;   // 创建时间

    private LocalDateTime updateTime;   // 更新时间
}
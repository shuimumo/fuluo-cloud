package com.shuimumu.fuluogame.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class CommentVO {
    private Long id;
    private Long postId;
    private Long parentId;
    private String postTitle;
    private Long userId;
    private String username;
    private String avatar;
    private String content;
    private Integer likes;
    private LocalDateTime createTime;
    private long replyCount;
    private boolean isAuthor;
    private boolean liked;
    private Integer status;
    private String moderationDesc;
    private String moderationWords;
}

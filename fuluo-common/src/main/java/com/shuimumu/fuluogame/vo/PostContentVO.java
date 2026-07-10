package com.shuimumu.fuluogame.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class PostContentVO {
    private Long id;
    private Long userId;
    private String username;
    private String avatar;
    private String title;
    private String content;
    private List<String> images;
    private LocalDateTime createTime;
    private List<String> topics;
    private Integer status;
    private String moderationDesc;
    private String moderationWords;
    private String titleHighlight;
    private String contentHighlight;
    // 计数 — 实时 Redis
    private int likes;
    private int comments;
    private int views;
    // 状态 — 实时 Redis
    private boolean liked;
    private boolean following;
}

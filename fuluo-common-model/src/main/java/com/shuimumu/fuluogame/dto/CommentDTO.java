package com.shuimumu.fuluogame.dto;

import lombok.Data;

@Data
public class CommentDTO {
    private String content;
    private Long parentId;    // 0=根评论
}

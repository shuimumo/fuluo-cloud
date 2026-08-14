package com.shuimumu.fuluogame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CommentModerationMsg {
    private Long commentId;
    private Long userId;
    private Long postId;
    private Long parentId;    // 0=根评论
    private Long rootId;      // 0=根评论
}

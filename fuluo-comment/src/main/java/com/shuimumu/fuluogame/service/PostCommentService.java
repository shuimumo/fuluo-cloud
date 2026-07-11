package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.vo.CommentVO;
import com.shuimumu.fuluogame.vo.PageVO;

public interface PostCommentService {
    void create(Long userId, Long postId, String content, Long parentId);
    PageVO<CommentVO> list(Long postId, int page, int size);
    PageVO<CommentVO> list(Long postId, int page, int size, String sort);
    PageVO<CommentVO> replies(Long rootId, int page, int size);
    boolean like(Long commentId, Long userId);
    boolean unlike(Long commentId, Long userId);
}

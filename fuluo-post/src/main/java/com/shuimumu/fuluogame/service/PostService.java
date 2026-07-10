package com.shuimumu.fuluogame.service;

import com.shuimumu.fuluogame.dto.PostDTO;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.PostContentVO;

public interface PostService {
    void publishPost(PostDTO postDTO);

    PageVO<PostContentVO> getPostList(String tab, int page, int size);

    PostContentVO getPostById(Long id);

    boolean like(Long userId, Long postId, Long authorId);

    boolean unlike(Long userId, Long postId, Long authorId);

    Long getPostAuthorId(Long id);

    void updatePost(Long postId, PostDTO postDTO);

    void deletePost(Long postId);
}

package com.shuimumu.fuluogame.controller;
import com.shuimumu.fuluogame.dto.PostDTO;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.service.PostService;
import com.shuimumu.fuluogame.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Tag(name = "帖子接口")
@RestController
@RequestMapping("/post")
public class PostController {

    @Autowired
    private PostService postService;

    @Operation(summary = "发布帖子")
    @PostMapping("/publish")
    public Result publishPost(@RequestBody PostDTO postDTO) {
        postService.publishPost(postDTO);
        return Result.success();
    }

    @Operation(summary = "帖子列表")
    @GetMapping("/list")
    public Result<PageVO<PostContentVO>> list(
            @RequestParam(defaultValue = "latest") String tab,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageVO<PostContentVO> pageVO = postService.getPostList(tab, page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "帖子详情")
    @GetMapping("/{id}")
    public Result<PostContentVO> detail(@PathVariable Long id) {
        PostContentVO vo = postService.getPostById(id);
        if (vo == null) return Result.error("帖子不存在，或已被删除");
        return Result.success(vo);
    }

    @Operation(summary = "编辑帖子")
    @PutMapping("/{id}")
    public Result update(@PathVariable Long id, @RequestBody PostDTO postDTO) {
        postService.updatePost(id, postDTO);
        return Result.success();
    }

    @Operation(summary = "删除帖子")
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        postService.deletePost(id);
        return Result.success();
    }

    // 搜索已迁至 fuluo-search 服务（GET /search），本服务不再提供 /post/search

    @Operation(summary = "点赞")
    @PostMapping("/{id}/like")
    public Result like(@PathVariable Long id) {
        Long authorId = postService.getPostAuthorId(id);
        if (authorId == null) return Result.error("帖子不存在");
        boolean ok = postService.like(UserContext.getUser().getId(), id, authorId);
        return Result.success(Map.of("liked", ok));
    }

    @Operation(summary = "取消赞")
    @PostMapping("/{id}/unlike")
    public Result unlike(@PathVariable Long id) {
        Long authorId = postService.getPostAuthorId(id);
        if (authorId == null) return Result.error("帖子不存在");
        boolean ok = postService.unlike(UserContext.getUser().getId(), id, authorId);
        return Result.success(Map.of("liked", !ok));
    }
}

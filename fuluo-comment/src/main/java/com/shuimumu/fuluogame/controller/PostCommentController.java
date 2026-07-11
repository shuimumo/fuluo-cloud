package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.dto.CommentDTO;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.service.PostCommentService;
import com.shuimumu.fuluogame.vo.CommentVO;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "评论接口")
@RestController
@RequestMapping("/post")
public class PostCommentController {

    @Autowired
    private PostCommentService postCommentService;

    @Operation(summary = "发表评论")
    @PostMapping("/{id}/comment")
    public Result create(@PathVariable Long id, @RequestBody CommentDTO dto) {
        if (dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            return Result.error("评论内容不能为空");
        }
        if (dto.getContent().length() > 2000) {
            return Result.error("评论内容不能超过2000字");
        }
        Long parentId = dto.getParentId() != null ? dto.getParentId() : 0L;
        Long userId = UserContext.getUser().getId();
        postCommentService.create(userId, id, dto.getContent().trim(), parentId);
        return Result.success();
    }

    @Operation(summary = "评论列表")
    @GetMapping("/{id}/comments")
    public Result<PageVO<CommentVO>> list(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "latest") String sort) {
        return Result.success(postCommentService.list(id, page, size, sort));
    }

    @Operation(summary = "点赞评论")
    @PostMapping("/comment/{commentId}/like")
    public Result like(@PathVariable Long commentId) {
        Long userId = UserContext.getUser().getId();
        boolean ok = postCommentService.like(commentId, userId);
        return Result.success(Map.of("liked", ok));
    }

    @Operation(summary = "取消点赞评论")
    @PostMapping("/comment/{commentId}/unlike")
    public Result unlike(@PathVariable Long commentId) {
        Long userId = UserContext.getUser().getId();
        boolean ok = postCommentService.unlike(commentId, userId);
        return Result.success(Map.of("liked", !ok));
    }

    @Operation(summary = "回复列表")
    @GetMapping("/comment/{commentId}/replies")
    public Result<PageVO<CommentVO>> replies(
            @PathVariable Long commentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size) {
        return Result.success(postCommentService.replies(commentId, page, size));
    }
}

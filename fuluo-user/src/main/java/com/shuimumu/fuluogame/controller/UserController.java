package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.dto.UserDTO;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.service.UserService;
import com.shuimumu.fuluogame.vo.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "用户模块")
@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Operation(summary = "用户信息")
    @GetMapping("/profile")
    public Result<UserVO> profile() {
        UserVO userVO = userService.getProfile(UserContext.getUser().getId());
        return Result.success(userVO);
    }

    @Operation(summary = "更新用户信息")
    @PutMapping("/profile")
    public Result updateProfile(@RequestBody UserDTO userDTO) {
        userService.updateProfile(userDTO);
        return Result.success();
    }

    @Operation(summary = "他人主页")
    @GetMapping("/{id}")
    public Result<UserProfileVO> publicProfile(@PathVariable Long id) {
        Long currentUserId = null;
        try {
            currentUserId = UserContext.getUser().getId();
        } catch (Exception ignored) {
        }
        UserProfileVO vo = userService.getPublicProfile(id, currentUserId);
        return Result.success(vo);
    }

    @Operation(summary = "关注用户")
    @PostMapping("/follow/{id}")
    public Result follow(@PathVariable Long id) {
        userService.follow(UserContext.getUser().getId(), id);
        return Result.success();
    }

    @Operation(summary = "取消关注")
    @PostMapping("/unfollow/{id}")
    public Result unfollow(@PathVariable Long id) {
        userService.unfollow(UserContext.getUser().getId(), id);
        return Result.success();
    }

    @Operation(summary = "用户的关注列表")
    @GetMapping("/{id}/following")
    public Result<PageVO<FollowVO>> userFollowing(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        userService.checkPrivacy(id, "following");
        PageVO<FollowVO> pageVO = userService.getFollowList(id, "following", page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "用户的粉丝列表")
    @GetMapping("/{id}/followers")
    public Result<PageVO<FollowVO>> userFollowers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        userService.checkPrivacy(id, "followers");
        PageVO<FollowVO> pageVO = userService.getFollowList(id, "followers", page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "用户帖子列表")
    @GetMapping("/{id}/posts")
    public Result<PageVO<PostContentVO>> userPosts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        userService.checkPrivacy(id, "posts");
        PageVO<PostContentVO> pageVO = userService.getUserPosts(id, page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "用户赞过列表")
    @GetMapping("/{id}/liked")
    public Result<PageVO<PostContentVO>> userLiked(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        userService.checkPrivacy(id, "liked");
        PageVO<PostContentVO> pageVO = userService.getUserLiked(id, page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "我的帖子")
    @GetMapping("/posts")
    public Result<PageVO<PostContentVO>> myPosts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageVO<PostContentVO> pageVO = userService.getUserPosts(UserContext.getUser().getId(), page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "我的评论")
    @GetMapping("/comments")
    public Result<PageVO<CommentVO>> myComments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageVO<CommentVO> pageVO = userService.getUserComments(UserContext.getUser().getId(), page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "赞过的内容")
    @GetMapping("/liked")
    public Result<PageVO<PostContentVO>> myLiked(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageVO<PostContentVO> pageVO = userService.getUserLiked(UserContext.getUser().getId(), page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "关注列表")
    @GetMapping("/following")
    public Result<PageVO<FollowVO>> following(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageVO<FollowVO> pageVO = userService.getFollowList(UserContext.getUser().getId(), "following", page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "粉丝列表")
    @GetMapping("/followers")
    public Result<PageVO<FollowVO>> followers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageVO<FollowVO> pageVO = userService.getFollowList(UserContext.getUser().getId(), "followers", page, size);
        return Result.success(pageVO);
    }

    @Operation(summary = "收件箱")
    @GetMapping("/inbox")
    public Result<PageVO<PostContentVO>> inbox(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.success(userService.getInbox(UserContext.getUser().getId(), page, size));
    }

}

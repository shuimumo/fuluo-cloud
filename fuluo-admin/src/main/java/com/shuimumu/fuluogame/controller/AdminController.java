package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.entity.Announcement;
import com.shuimumu.fuluogame.entity.Banner;
import com.shuimumu.fuluogame.entity.Comment;
import com.shuimumu.fuluogame.entity.Post;
import com.shuimumu.fuluogame.entity.Topic;
import com.shuimumu.fuluogame.service.AdminService;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "管理后台")
@RestController
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @Operation(summary = "轮播图列表")
    @GetMapping("/banner")
    public Result<List<Banner>> listBanners() { return Result.success(adminService.listBanners()); }

    @Operation(summary = "新增轮播图")
    @PostMapping("/banner")
    public Result saveBanner(@RequestBody Banner banner) { adminService.saveBanner(banner); return Result.success(); }

    @Operation(summary = "编辑轮播图")
    @PutMapping("/banner/{id}")
    public Result updateBanner(@PathVariable Long id, @RequestBody Banner banner) { banner.setId(id); adminService.updateBanner(banner); return Result.success(); }

    @Operation(summary = "删除轮播图")
    @DeleteMapping("/banner/{id}")
    public Result deleteBanner(@PathVariable Long id) { adminService.deleteBanner(id); return Result.success(); }

    @Operation(summary = "公告列表")
    @GetMapping("/announcement")
    public Result<List<Announcement>> listAnnouncements() { return Result.success(adminService.listAnnouncements()); }

    @Operation(summary = "发布公告")
    @PostMapping("/announcement")
    public Result saveAnnouncement(@RequestBody Announcement a) { adminService.saveAnnouncement(a); return Result.success(); }

    @Operation(summary = "编辑公告")
    @PutMapping("/announcement/{id}")
    public Result updateAnnouncement(@PathVariable Long id, @RequestBody Announcement a) { a.setId(id); adminService.updateAnnouncement(a); return Result.success(); }

    @Operation(summary = "删除公告")
    @DeleteMapping("/announcement/{id}")
    public Result deleteAnnouncement(@PathVariable Long id) { adminService.deleteAnnouncement(id); return Result.success(); }

    @Operation(summary = "新增话题")
    @PostMapping("/topic")
    public Result saveTopic(@RequestBody Topic topic) { adminService.saveTopic(topic); return Result.success(); }

    @Operation(summary = "编辑话题")
    @PutMapping("/topic/{id}")
    public Result updateTopic(@PathVariable Long id, @RequestBody Topic topic) { topic.setId(id); adminService.updateTopic(topic); return Result.success(); }

    @Operation(summary = "删除话题")
    @DeleteMapping("/topic/{id}")
    public Result deleteTopic(@PathVariable Long id) { adminService.deleteTopic(id); return Result.success(); }

    @Operation(summary = "帖子列表")
    @GetMapping("/posts")
    public Result<PageVO<Post>> listPosts(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(adminService.listPosts(status, page, size));
    }

    @Operation(summary = "评论列表")
    @GetMapping("/comments")
    public Result<PageVO<Comment>> listComments(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(adminService.listComments(status, page, size));
    }

    @Operation(summary = "通过帖子")
    @PutMapping("/post/{id}/approve")
    public Result approvePost(@PathVariable Long id) { adminService.approvePost(id); return Result.success(); }

    @Operation(summary = "拒绝帖子")
    @PutMapping("/post/{id}/reject")
    public Result rejectPost(@PathVariable Long id, @RequestBody Map<String, String> body) {
        adminService.rejectPost(id, body.getOrDefault("reason", "")); return Result.success();
    }

    @Operation(summary = "通过评论")
    @PutMapping("/comment/{id}/approve")
    public Result approveComment(@PathVariable Long id) { adminService.approveComment(id); return Result.success(); }

    @Operation(summary = "拒绝评论")
    @PutMapping("/comment/{id}/reject")
    public Result rejectComment(@PathVariable Long id, @RequestBody Map<String, String> body) {
        adminService.rejectComment(id, body.getOrDefault("reason", "")); return Result.success();
    }

}

package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.service.NotificationService;
import com.shuimumu.fuluogame.vo.NotificationVO;
import com.shuimumu.fuluogame.vo.PageVO;
import com.shuimumu.fuluogame.vo.Result;
import com.shuimumu.fuluogame.vo.UnreadSummaryVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "通知接口")
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Operation(summary = "服务健康检查")
    @GetMapping("/health")
    public Result<String> health() {
        return Result.success("ok");
    }

    @Operation(summary = "通知列表")
    @GetMapping
    public Result<PageVO<NotificationVO>> list(
            @RequestParam(defaultValue = "all") String category,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(50, Math.max(1, size));
        return Result.success(notificationService.list(currentUserId(), category, safePage, safeSize));
    }

    @Operation(summary = "未读通知汇总")
    @GetMapping("/unread")
    public Result<UnreadSummaryVO> unread() {
        return Result.success(notificationService.unreadSummary(currentUserId()));
    }

    @Operation(summary = "标记单条通知已读")
    @PutMapping("/{id}/read")
    public Result<Map<String, Boolean>> markRead(@PathVariable Long id) {
        return Result.success(Map.of("updated", notificationService.markRead(currentUserId(), id)));
    }

    @Operation(summary = "按分类全部已读")
    @PutMapping("/read-all")
    public Result<Map<String, Integer>> markAllRead(
            @RequestParam(defaultValue = "all") String category) {
        return Result.success(Map.of("updated", notificationService.markAllRead(currentUserId(), category)));
    }

    private Long currentUserId() {
        return UserContext.getUser().getId();
    }
}

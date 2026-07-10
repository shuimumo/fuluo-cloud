package com.shuimumu.fuluogame.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 占位健康端点：确认服务可启动并已注册到 Nacos、网关可路由到达。
 * Phase 2 将从单体迁入真实的 评论服务 控制器/服务/消费者。
 */
@RestController
public class CommentHealthController {

    @GetMapping("/comment/health")
    public String health() {
        return "fuluo-comment up (phase1 skeleton)";
    }
}

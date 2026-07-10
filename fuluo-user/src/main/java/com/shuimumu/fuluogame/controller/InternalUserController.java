package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.service.UserService;
import com.shuimumu.fuluogame.vo.UserVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * 服务间内部接口（对应 {@code com.shuimumu.fuluogame.api.UserApi}）。
 * 仅供内网 Feign 调用，不经网关对外暴露。
 */
@RestController
@RequestMapping("/internal/user")
public class InternalUserController {

    private final UserService userService;

    public InternalUserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/batch-profiles")
    public List<UserVO> batchProfiles(@RequestBody List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyList();
        return new ArrayList<>(userService.batchGetProfiles(new HashSet<>(userIds)).values());
    }
}

package com.shuimumu.fuluogame.api;

import com.shuimumu.fuluogame.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 高可用降级：用户服务不可用/超时/熔断时返回空列表，
 * 调用方（如帖子列表）降级为"无作者信息"而不是整体 500。
 */
@Slf4j
@Component
public class UserApiFallbackFactory implements FallbackFactory<UserApi> {

    @Override
    public UserApi create(Throwable cause) {
        log.warn("UserApi 调用降级: {}", cause.getMessage());
        return userIds -> Collections.<UserVO>emptyList();
    }
}

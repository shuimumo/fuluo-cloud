package com.shuimumu.fuluogame.client;

import com.shuimumu.fuluogame.api.UserApi;
import com.shuimumu.fuluogame.vo.UserVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户信息拉取适配器：把单体里的 {@code userService.batchGetProfiles(Set)->Map} 语义，
 * 桥接到跨服务的 {@link UserApi} Feign 调用（返回 List 后本地组 Map）。
 *
 * <p>保持与原方法相同签名，使 {@code PostServiceImpl}/{@code TopicServiceImpl} 的调用点几乎不变；
 * 用户服务不可用时由 {@code UserApiFallbackFactory} 降级为空，Map 为空，帖子仍可渲染。
 */
@Component
public class UserProfileFetcher {

    private final UserApi userApi;

    public UserProfileFetcher(UserApi userApi) {
        this.userApi = userApi;
    }

    public Map<Long, UserVO> batchGetProfiles(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        List<UserVO> list = userApi.batchProfiles(new ArrayList<>(userIds));
        if (list == null || list.isEmpty()) return Collections.emptyMap();
        Map<Long, UserVO> map = new HashMap<>(list.size());
        for (UserVO u : list) {
            if (u != null && u.getId() != null) map.put(u.getId(), u);
        }
        return map;
    }
}

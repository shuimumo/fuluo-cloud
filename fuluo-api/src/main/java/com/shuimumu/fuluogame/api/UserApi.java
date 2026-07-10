package com.shuimumu.fuluogame.api;

import com.shuimumu.fuluogame.vo.UserVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 用户服务的服务间调用契约（OpenFeign）。
 *
 * <p>帖子/评论/话题等服务通过它批量拉取作者信息做视图填充，取代单体里的
 * {@code userService.batchGetProfiles(...)} 直接方法调用。
 *
 * <p>返回 {@code List<UserVO>} 而非 {@code Map<Long,UserVO>}：Feign/JSON 对
 * {@code Map<Long,V>} 的键反序列化不稳定，调用方自行组 Map。
 */
@FeignClient(name = "fuluo-user", contextId = "userApi", path = "/internal/user",
        fallbackFactory = UserApiFallbackFactory.class)
public interface UserApi {

    @PostMapping("/batch-profiles")
    List<UserVO> batchProfiles(@RequestBody List<Long> userIds);
}

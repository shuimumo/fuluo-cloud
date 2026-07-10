package com.shuimumu.fuluogame.service.Impl;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.shuimumu.fuluogame.config.RabbitMQConfig;
import com.shuimumu.fuluogame.constants.SystemConstants;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.constants.UserRedisKeys;
import com.shuimumu.fuluogame.dto.LoginDTO;
import com.shuimumu.fuluogame.entity.User;
import com.shuimumu.fuluogame.exception.BusinessException;
import com.shuimumu.fuluogame.mapper.UserMapper;
import com.shuimumu.fuluogame.service.LoginService;
import com.shuimumu.fuluogame.service.UserService;
import com.shuimumu.fuluogame.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
@Slf4j
@Service
public class LoginServiceImpl extends ServiceImpl<UserMapper, User> implements LoginService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private UserService userService;
    @Override
    public String login(LoginDTO loginDTO) {
        String code = stringRedisTemplate.opsForValue().get(SystemRedisKeys.loginCode(loginDTO.getPhone()));
        if (!java.util.Objects.equals(loginDTO.getCode(), code)) {
            throw new BusinessException("验证码错误");
        }
        User user = query().eq("phone", loginDTO.getPhone()).one();
        if (user == null) {
            user = creatUserWithPhone(loginDTO.getPhone());
        }
        String token = UUID.randomUUID().toString();
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        String userVOJson = JSONUtil.toJsonStr(userVO);
        stringRedisTemplate.opsForValue().set(SystemRedisKeys.loginToken(token), userVOJson,
                SystemRedisKeys.LOGIN_USER_TTL, TimeUnit.DAYS);
        markActiveAndBackfillIfNeeded(user.getId());
        log.info("登录成功，token：{}", token);
        return token;
    }

    private User creatUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setUsername(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6));
        user.setAvatar(SystemConstants.DEFAULT_AVATAR);
        save(user);
        // 初始化 Redis 统计
        String key = UserRedisKeys.stats(user.getId());
        stringRedisTemplate.opsForHash().put(key, "posts", "0");
        stringRedisTemplate.opsForHash().put(key, "following", "0");
        stringRedisTemplate.opsForHash().put(key, "followers", "0");
        stringRedisTemplate.opsForHash().put(key, "likes", "0");
        return user;
    }

    @Override
    public void sendCode(String phone) {
        // 60秒内不允许重复发送
        String intervalKey = SystemRedisKeys.loginCodeInterval(phone);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(intervalKey))) {
            throw new BusinessException("验证码已发送，请60秒后重试");
        }

        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(SystemRedisKeys.loginCode(phone), code,
                SystemRedisKeys.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(intervalKey, "1",
                SystemRedisKeys.LOGIN_CODE_INTERVAL_TTL, TimeUnit.SECONDS);
        log.info("验证码发送成功，验证码：{}", code);
    }

    @Override
    public Map<String, Object> loginByPassword(String phone, String hashedPassword) {
        User user = query().eq("phone", phone).one();
        if (user == null || user.getPassword() == null) {
            throw new BusinessException("账号或密码错误");
        }
        if (!BCrypt.checkpw(hashedPassword, user.getPassword())) {
            throw new BusinessException("账号或密码错误");
        }
        String token = UUID.randomUUID().toString();
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        stringRedisTemplate.opsForValue().set(SystemRedisKeys.loginToken(token),
                JSONUtil.toJsonStr(userVO), SystemRedisKeys.LOGIN_USER_TTL, TimeUnit.DAYS);
        markActiveAndBackfillIfNeeded(user.getId());
        log.info("密码登录成功 userId={}", user.getId());
        return Map.of("token", token);
    }

    /**
     * 登录时打活跃标记，若用户此前不活跃（或首次），发 backfill MQ 补拉关注者的近期帖。
     */
    private void markActiveAndBackfillIfNeeded(Long userId) {
        long now = System.currentTimeMillis() / 1000;
        String userIdStr = userId.toString();
        Double prev = stringRedisTemplate.opsForZSet().score(UserRedisKeys.activeUsers(), userIdStr);
        stringRedisTemplate.opsForZSet().add(UserRedisKeys.activeUsers(), userIdStr, now);
        if (prev == null || now - prev > UserRedisKeys.ACTIVE_WINDOW_SEC) {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.KEY_BACKFILL,
                    Map.of("userId", userId, "sinceTs", prev == null ? 0L : prev.longValue()));
            log.info("触发补拉 backfill userId={} sinceTs={}", userId,
                    prev == null ? 0 : prev.longValue());
        }
    }

    @Override
    public void setPassword(Long userId, String hashedPassword) {
        User user = getById(userId);
        if (user == null) throw new BusinessException("用户不存在");
        String hash = BCrypt.hashpw(hashedPassword, BCrypt.gensalt());
        user.setPassword(hash);
        updateById(user);
        log.info("密码设置成功 userId={}", userId);
    }
}

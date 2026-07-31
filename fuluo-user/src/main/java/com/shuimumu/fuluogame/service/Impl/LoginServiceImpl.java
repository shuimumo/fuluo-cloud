package com.shuimumu.fuluogame.service.Impl;

import com.shuimumu.fuluogame.utils.UserStatsRedisHelper;
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
    private UserStatsRedisHelper userStatsRedisHelper;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private UserService userService;

    @Override
    public String login(LoginDTO loginDTO) {
        checkLoginLock(loginDTO.getPhone());
        String code = stringRedisTemplate.opsForValue().get(SystemRedisKeys.loginCode(loginDTO.getPhone()));
        if (!java.util.Objects.equals(loginDTO.getCode(), code)) {
            recordLoginFail(loginDTO.getPhone());
            throw new BusinessException("验证码错误");
        }
        User user = query().eq("phone", loginDTO.getPhone()).one();
        if (user == null) {
            user = creatUserWithPhone(loginDTO.getPhone());
        }
        clearLoginFail(loginDTO.getPhone());
        String token = UUID.randomUUID().toString();
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        String userVOJson = JSONUtil.toJsonStr(userVO);
        stringRedisTemplate.opsForValue().set(SystemRedisKeys.loginToken(token), userVOJson,
                SystemRedisKeys.LOGIN_USER_TTL, TimeUnit.DAYS);
        markActiveAndBackfillIfNeeded(user.getId());
        log.info("登录成功 userId={}", user.getId());
        return token;
    }

    private User creatUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setUsername(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6));
        user.setAvatar(SystemConstants.DEFAULT_AVATAR);
        save(user);
        userStatsRedisHelper.initZeroStats(user.getId());
        return user;
    }

    @Override
    public void sendCode(String phone) {
        // 60 秒内不允许重复发送
        String intervalKey = SystemRedisKeys.loginCodeInterval(phone);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(intervalKey))) {
            throw new BusinessException("验证码已发送，请60秒后重试");
        }
        // 单手机号每日发送上限，防短信轰炸
        String dailyKey = SystemRedisKeys.loginCodeDailyCount(phone);
        String daily = stringRedisTemplate.opsForValue().get(dailyKey);
        if (daily != null && Long.parseLong(daily) >= SystemRedisKeys.LOGIN_CODE_DAILY_MAX) {
            throw new BusinessException("今日验证码发送次数已达上限");
        }

        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(SystemRedisKeys.loginCode(phone), code,
                SystemRedisKeys.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(intervalKey, "1",
                SystemRedisKeys.LOGIN_CODE_INTERVAL_TTL, TimeUnit.SECONDS);
        Long cnt = stringRedisTemplate.opsForValue().increment(dailyKey);
        if (cnt != null && cnt == 1L) {
            stringRedisTemplate.expire(dailyKey, 1, TimeUnit.DAYS);
        }
        // 生产环境应改为真实短信/邮件发送，日志只留脱敏信息
        log.info("验证码发送成功 phone={}", phone);
    }

    @Override
    public Map<String, Object> loginByPassword(String phone, String hashedPassword) {
        checkLoginLock(phone);
        User user = query().eq("phone", phone).one();
        if (user == null || user.getPassword() == null) {
            recordLoginFail(phone);
            throw new BusinessException("账号或密码错误");
        }
        if (!BCrypt.checkpw(hashedPassword, user.getPassword())) {
            recordLoginFail(phone);
            throw new BusinessException("账号或密码错误");
        }
        clearLoginFail(phone);
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
     * 登录时打活跃标记，若用户此前不活跃（或首次），发 backfill MQ 补齐关注者的近期帖子。
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

    // ==================== 防爆破 ====================

    /** 连续失败达到上限则锁定一段时间，拒绝继续尝试。 */
    private void checkLoginLock(String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        String v = stringRedisTemplate.opsForValue().get(SystemRedisKeys.loginFail(phone));
        if (v != null) {
            long fails = Long.parseLong(v);
            if (fails >= SystemRedisKeys.LOGIN_FAIL_MAX) {
                throw new BusinessException("尝试次数过多，请" + SystemRedisKeys.LOGIN_FAIL_LOCK_MINUTES + "分钟后再试");
            }
        }
    }

    /** 记录一次失败（首次失败时给计数 key 设置锁定 TTL）。 */
    private void recordLoginFail(String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        String key = SystemRedisKeys.loginFail(phone);
        Long n = stringRedisTemplate.opsForValue().increment(key);
        if (n != null && n == 1L) {
            stringRedisTemplate.expire(key, SystemRedisKeys.LOGIN_FAIL_LOCK_MINUTES, TimeUnit.MINUTES);
        }
    }

    private void clearLoginFail(String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(SystemRedisKeys.loginFail(phone));
    }
}
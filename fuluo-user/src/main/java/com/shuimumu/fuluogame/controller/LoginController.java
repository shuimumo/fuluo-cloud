package com.shuimumu.fuluogame.controller;

import com.shuimumu.fuluogame.dto.LoginDTO;
import com.shuimumu.fuluogame.dto.SendCodeDTO;
import com.shuimumu.fuluogame.interceptor.UserContext;
import com.shuimumu.fuluogame.service.LoginService;
import com.shuimumu.fuluogame.utils.RegexUtils;
import com.shuimumu.fuluogame.vo.Result;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

@Tag(name = "用户相关接口")
@RestController
@RequestMapping("/user")
public class LoginController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final int MAX_FAILS = 5;
    private static final int BLOCK_MINUTES = 30;

    private boolean isBlocked(String phone) {
        return stringRedisTemplate.hasKey("login:block:" + phone);
    }

    private void recordFail(String phone) {
        String key = "login:fail:" + phone;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1) stringRedisTemplate.expire(key, 15, TimeUnit.MINUTES);
        if (count != null && count >= MAX_FAILS) {
            stringRedisTemplate.opsForValue().set("login:block:" + phone, "1", BLOCK_MINUTES, TimeUnit.MINUTES);
            stringRedisTemplate.delete(key);
        }
    }

    private void clearFails(String phone) {
        stringRedisTemplate.delete("login:fail:" + phone);
        stringRedisTemplate.delete("login:block:" + phone);
    }

    @Operation(summary = "验证码登录")
    @PostMapping("/login")
    public Result login(@RequestBody LoginDTO loginDTO) {
        if (RegexUtils.isPhoneInvalid(loginDTO.getPhone())) return Result.error("手机号格式错误");
        if (isBlocked(loginDTO.getPhone())) return Result.error("尝试次数过多，请30分钟后重试");
        try {
            String token = loginService.login(loginDTO);
            clearFails(loginDTO.getPhone());
            return Result.success(Map.of("token", token));
        } catch (Exception e) {
            recordFail(loginDTO.getPhone());
            throw e;
        }
    }
    @Operation(summary = "发送验证码")
    @PostMapping("/sendCode")
    public Result sendCode(@RequestBody SendCodeDTO sendCodeDTO) {
        if(RegexUtils.isPhoneInvalid(sendCodeDTO.getPhone())) {
            return Result.error("手机号格式错误");
        }
        loginService.sendCode(sendCodeDTO.getPhone());
        return Result.success();
    }
    @Operation(summary = "密码登录")
    @PostMapping("/login/password")
    public Result loginByPassword(@RequestBody LoginDTO dto) {
        if (dto.getPassword() == null || dto.getPassword().isEmpty()) return Result.error("密码不能为空");
        if (RegexUtils.isPhoneInvalid(dto.getPhone())) return Result.error("手机号格式错误");
        if (isBlocked(dto.getPhone())) return Result.error("尝试次数过多，请30分钟后重试");
        try {
            return Result.success(loginService.loginByPassword(dto.getPhone(), dto.getPassword()));
        } catch (Exception e) {
            recordFail(dto.getPhone());
            throw e;
        }
    }
    @Operation(summary = "设置密码")
    @PutMapping("/password")
    public Result setPassword(@RequestBody Map<String, String> body) {
        String password = body.get("password");
        if (password == null || password.isEmpty()) return Result.error("密码不能为空");
        loginService.setPassword(UserContext.getUser().getId(), password);
        return Result.success();
    }
}

package com.shuimumu.fuluogame.controller;

import cn.hutool.json.JSONUtil;
import com.shuimumu.fuluogame.constants.SystemRedisKeys;
import com.shuimumu.fuluogame.entity.Announcement;
import com.shuimumu.fuluogame.entity.Banner;
import com.shuimumu.fuluogame.vo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class PublicController {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/banners")
    public Result<List<Banner>> banners() {
        String json = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cacheBanners());
        if (json == null) return Result.success(List.of());
        return Result.success(JSONUtil.toList(json, Banner.class));
    }

    @GetMapping("/announcements")
    public Result<List<Announcement>> announcements() {
        String json = stringRedisTemplate.opsForValue().get(SystemRedisKeys.cacheAnnouncements());
        if (json == null) return Result.success(List.of());
        return Result.success(JSONUtil.toList(json, Announcement.class));
    }

}

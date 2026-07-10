package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.UserStat;

import java.util.List;

public interface UserStatMapper extends BaseMapper<UserStat> {
    void upsertBatch(List<UserStat> userStats);
}

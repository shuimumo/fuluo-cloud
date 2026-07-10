package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.Topic;

import java.util.List;

public interface TopicMapper extends BaseMapper<Topic> {
    void upsertBatch(List<Topic> topics);
}

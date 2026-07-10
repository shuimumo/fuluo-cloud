package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.Post;

import java.util.List;

public interface PostMapper extends BaseMapper<Post> {
    void upsertBatch(List<Post> posts);
}

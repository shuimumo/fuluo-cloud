package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.Comment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CommentMapper extends BaseMapper<Comment> {

    /** 批量更新审核状态（CASE WHEN，一次 UPDATE 处理整批，用于审核消费者批量回写） */
    void updateStatusBatch(@Param("list") List<Comment> comments);
}

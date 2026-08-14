package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.PostTopic;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PostTopicMapper extends BaseMapper<PostTopic> {

    /**
     * P1 修复：批量插入帖子-话题关联。
     * 原实现每帖每话题一次单条 INSERT（新帖 N 个话题即 N 次 DB 往返，是落库链路的剩余瓶颈），
     * 这里合并为一次批量 INSERT，配合 uk_post_topic 唯一键的 ON DUPLICATE KEY 保持幂等（MQ 重试安全）。
     */
    int insertBatch(@Param("list") List<PostTopic> list);
}

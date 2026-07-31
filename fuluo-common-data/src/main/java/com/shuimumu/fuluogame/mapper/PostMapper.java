package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.Post;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PostMapper extends BaseMapper<Post> {
    void upsertBatch(List<Post> posts);

    /** 公开帖数量（status=0） */
    long countPublished();

    /** 最新公开帖 id，按 create_time desc, id desc */
    List<Long> selectLatestIds(@Param("offset") long offset, @Param("limit") int limit);

    /** 话题下公开帖数量 */
    long countPublishedByTopic(@Param("topicId") long topicId);

    /** 话题下最新公开帖 id */
    List<Long> selectLatestIdsByTopic(@Param("topicId") long topicId,
                                      @Param("offset") long offset,
                                      @Param("limit") int limit);
}

package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.Post;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PostMapper extends BaseMapper<Post> {
    void upsertBatch(List<Post> posts);
    /** 批量插入新帖（ON DUPLICATE KEY 幂等，用于消费者批量落库） */
    void insertBatch(@Param("list") List<Post> posts);

    /** 批量更新审核状态（CASE WHEN，一次 UPDATE 处理整批，用于审核消费者批量回写） */
    void updateStatusBatch(@Param("list") List<Post> posts);

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

package com.shuimumu.fuluogame.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shuimumu.fuluogame.entity.Follow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * P2 修复：关注列表 DB 分页，取代「全量 SMEMBERS + 内存分页」。
     * follow 表 uk_follow(follower_id, followed_id) 前缀索引覆盖 follower_id 查询；
     * status=1 过滤已取关记录。
     */
    @Select("SELECT followed_id FROM `follow` WHERE follower_id = #{userId} AND status = 1 " +
            "ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{size}")
    List<Long> selectFollowingIds(@Param("userId") Long userId,
                                  @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT follower_id FROM `follow` WHERE followed_id = #{userId} AND status = 1 " +
            "ORDER BY create_time DESC, id DESC LIMIT #{offset}, #{size}")
    List<Long> selectFollowerIds(@Param("userId") Long userId,
                                 @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM `follow` WHERE follower_id = #{userId} AND status = 1")
    long countFollowing(@Param("userId") Long userId);

    @Select("SELECT COUNT(*) FROM `follow` WHERE followed_id = #{userId} AND status = 1")
    long countFollowers(@Param("userId") Long userId);
}

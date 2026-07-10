package com.shuimumu.fuluogame.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_stat")
public class UserStat {

    @TableId
    private Long userId;

    private Long posts;

    private Long following;

    private Long followers;

    private Long likes;
}

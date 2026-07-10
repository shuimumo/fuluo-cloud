package com.shuimumu.fuluogame.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("follow")
public class Follow {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 主键ID

    private Long followerId;            // 关注者ID

    private Long followedId;            // 被关注者ID

    private Integer status;             // 状态

    private LocalDateTime createTime;   // 创建时间

    private LocalDateTime updateTime;   // 更新时间
}
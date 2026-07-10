package com.shuimumu.fuluogame.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;                    // 用户ID


    private String phone;               // 手机号


    private String password;            // 密码（BCrypt加密）


    private String username;            // 用户名


    private String avatar;              // 头像URL


    private Integer gender;             // 性别


    private LocalDate birthday;         // 生日


    private Integer status;             // 状态

    private String role;                // 角色: user=普通用户 admin=管理员

    private Integer postsPublic;        // 帖子公开: 1=是 0=否
    private Integer likedPublic;        // 点赞公开: 1=是 0=否
    private Integer followingPublic;    // 关注列表公开: 1=是 0=否
    private Integer followersPublic;    // 粉丝列表公开: 1=是 0=否

    private LocalDateTime createTime;   // 创建时间


    private LocalDateTime updateTime;   // 更新时间
}

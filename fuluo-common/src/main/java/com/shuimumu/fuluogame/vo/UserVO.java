package com.shuimumu.fuluogame.vo;

import lombok.Data;

import java.time.LocalDate;
@Data
public class UserVO {
    private Long id;                    // 用户ID


    private String phone;               // 手机号


    private String username;            // 用户名


    private String avatar;              // 头像URL


    private Integer gender;             // 性别


    private LocalDate birthday;         // 生日


    private Integer status;             // 状态
    private String role;                // 角色

    private Integer postsPublic;        // 帖子公开
    private Integer likedPublic;        // 点赞公开
    private Integer followingPublic;    // 关注列表公开
    private Integer followersPublic;    // 粉丝列表公开

    private long posts;                 // 帖子数

    private long following;             // 关注数

    private long followers;             // 粉丝数

    private long likes;                 // 获赞数
}

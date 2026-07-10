package com.shuimumu.fuluogame.dto;

import java.time.LocalDate;
import lombok.Data;

@Data
public class UserDTO {

    private String phone;               // 手机号


    private String username;            // 用户名


    private String avatar;              // 头像URL


    private Integer gender;             // 性别


    private LocalDate birthday;

    private Integer postsPublic;        // 帖子公开
    private Integer likedPublic;        // 点赞公开
    private Integer followingPublic;    // 关注列表公开
    private Integer followersPublic;    // 粉丝列表公开
}

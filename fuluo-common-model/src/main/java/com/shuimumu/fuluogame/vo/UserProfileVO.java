package com.shuimumu.fuluogame.vo;

import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileVO {
    private Long id;
    private String username;
    private String avatar;
    private Integer gender;
    private LocalDate birthday;
    // 统计
    private long posts;
    private long following;
    private long followers;
    private long likes;
    // 隐私
    private Integer postsPublic;
    private Integer likedPublic;
    private Integer followingPublic;
    private Integer followersPublic;
    // 当前用户是否已关注
    private boolean isFollowed;
}

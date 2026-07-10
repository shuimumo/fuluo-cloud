package com.shuimumu.fuluogame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncMessage {
    private String type;       // "follow" / "unfollow" / "like" / "unlike"
    private Long userId;       // 操作者
    private Long targetId;     // 目标（被关注者 / 帖子）
    private Long authorId;     // 帖子作者（like/follow 时用）
}

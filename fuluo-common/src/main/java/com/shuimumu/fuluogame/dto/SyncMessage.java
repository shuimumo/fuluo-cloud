package com.shuimumu.fuluogame.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
public class SyncMessage {
    private String type;       // "follow" / "unfollow" / "like" / "unlike"
    private Long userId;       // 操作者
    private Long targetId;     // 目标（被关注者 / 帖子）
    private Long authorId;     // 帖子作者（like/follow 时用）
    private String eventId;    // MQ 重试时保持不变，用于下游幂等

    public SyncMessage(String type, Long userId, Long targetId, Long authorId) {
        this(type, userId, targetId, authorId, UUID.randomUUID().toString());
    }

    public SyncMessage(String type, Long userId, Long targetId, Long authorId, String eventId) {
        this.type = type;
        this.userId = userId;
        this.targetId = targetId;
        this.authorId = authorId;
        this.eventId = eventId;
    }
}

package com.shuimumu.fuluogame.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {

    public static final String POST_LIKE = "POST_LIKE";
    public static final String COMMENT_LIKE = "COMMENT_LIKE";
    public static final String COMMENT = "COMMENT";
    public static final String FOLLOW = "FOLLOW";
    public static final String SYSTEM = "SYSTEM";

    private String eventId;
    private String type;
    private Long actorId;
    private Long recipientId;
    private Long targetId;
    private Long postId;
    private String message;
    private Long occurredAt;

    public static NotificationEvent interaction(String eventId, String type, Long actorId,
                                                Long targetId, Long postId) {
        return new NotificationEvent(eventId, type, actorId, null, targetId, postId,
                null, System.currentTimeMillis());
    }

    public static NotificationEvent system(String eventId, Long recipientId, Long targetId,
                                           Long postId, String message) {
        return new NotificationEvent(eventId, SYSTEM, null, recipientId, targetId, postId,
                message, System.currentTimeMillis());
    }
}

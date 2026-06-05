package com.tourism.notification.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForumNotificationEvent {
    private String  idempotencyKey;
    private String  eventType;
    private Integer recipientUserId;
    private Integer actorUserId;
    private String  actorName;
    private String  actorAvatar;
    private Integer postId;
    private String  postTitle;
    private Integer commentId;
    private Integer parentCommentId;

    // Số coin được thưởng — chỉ dùng cho eventType COIN_REWARD
    private java.math.BigDecimal coinAmount;
}
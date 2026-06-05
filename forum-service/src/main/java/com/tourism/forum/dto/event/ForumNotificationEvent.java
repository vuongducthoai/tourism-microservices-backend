package com.tourism.forum.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder    
@NoArgsConstructor
@AllArgsConstructor
public class ForumNotificationEvent {
     // Key duy nhất để chống duplicate — dùng UUID
    // VD: "POST_LIKED-postId5-userId2-1716300000000"
    private String idempotencyKey;

    // Loại event - Consumer dùng để switch - case
    // Giá trị: POST_LIKED | POST_COMMENTED | COMMENT_REPLIED |  COMMENT_LIKED ...
    private String eventType;

    // Người NHẬN thông báo (tác giả bài/comment/follower)
    private Integer recipientUserId;

    // Người THỰC HIỆN hành động (người like, comment, reply)
    private Integer actorUserId;
    private String actorName;
    private String actorAvatar;

    // Context - để frontend điều hướng khi click vào thông báo 
    private Integer postId;
    private String postTitle;
    private Integer commentId;
    private Integer parentCommentId;

    // Số coin được thưởng — chỉ dùng cho eventType COIN_REWARD
    private java.math.BigDecimal coinAmount;
}


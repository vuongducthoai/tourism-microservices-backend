package com.tourism.forum.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCommentResponse {
    private Integer commentId;
    private String content;
    private String contentPreview;
    private Integer likeCount;

    private Integer authorId;
    private String authorName;
    private String authorAvatar;

    private Integer postId;
    private String postTitle;
    private Integer parentCommentId;   // null = comment gốc, có giá trị = reply

    private String status;
    private Boolean isEdited;

    private String moderationLabel;
    private String moderationReason;
    private Double moderationScore;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}

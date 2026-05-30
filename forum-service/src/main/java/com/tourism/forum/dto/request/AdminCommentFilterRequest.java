package com.tourism.forum.dto.request;

import com.tourism.forum.entity.ContentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCommentFilterRequest {
    private ContentStatus status;
    private Integer postId;
    private Integer userId;
    private String moderationLabel;
    private String search;
}

package com.tourism.forum.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminForumStatsResponse {
    private Long totalPosts;
    private Long publishedPosts;
    private Long pendingPosts;
    private Long draftPosts;
    private Long hiddenPosts;
    private Long totalComments;
    private Long pendingComments;
}

package com.tourism.forum.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostDetailResponse {
    private Integer postId;
    private String title;
    private String content;
    private String summary;
    private String thumbnailUrl;
    private String postType;
    private String status;

    private Integer authorId;
    private String authorName;
    private String authorAvatar;

    private Integer categoryId;
    private String categoryName;

    private List<PostListResponse.TagInfo> tags;

    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private Integer bookmarkCount;
    private Integer shareCount;

    private Boolean isPinned;
    private Boolean isFeatured;

    private Boolean isLikedByCurrentUser;
    private Boolean isBookmarkedByCurrentUser;

    private List<CommentResponse> comments;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;
}

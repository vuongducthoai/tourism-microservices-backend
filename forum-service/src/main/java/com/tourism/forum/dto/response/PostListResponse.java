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
public class PostListResponse {
    private Integer postID;
    private String title;
    private String summary;
    private String thumbnailUrl;
    private String postType;

    private Integer authorId;
    private String authorName;
    private String authorAvatar;

    private Integer categoryId;
    private String categoryName;
    private String categorySlug;

    private List<TagInfo> tags;

    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private Integer bookmarkCount;

    private Boolean isPinned;
    private Boolean isFeatured;
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagInfo {
        private Integer tagId;
        private String tagName;
    }
}

package com.tourism.forum.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Chi tiết bài viết cho admin — gồm full content, ảnh, tags, thông tin tác giả,
 * moderation AI + lý do admin từ chối. Dùng cho Post Detail Modal.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPostDetailResponse {
    private Integer postId;
    private String title;
    private String content;        // full HTML
    private String summary;
    private String thumbnailUrl;
    private List<String> imageUrls;
    private List<PostListResponse.TagInfo> tags;
    private Integer categoryId;
    private String categoryName;
    private String status;
    private String postType;

    // Tác giả
    private Integer authorId;
    private String authorName;
    private String authorEmail;
    private String authorAvatar;

    // Moderation AI
    private Double moderationScore;
    private String moderationLabel;
    private String moderationReason;

    // Lý do admin từ chối/ẩn (khác moderationReason của AI)
    private String adminRejectionReason;

    // Stats
    private Integer viewCount;
    private Integer likeCount;
    private Integer commentCount;
    private Integer bookmarkCount;
    private Boolean isPinned;
    private Boolean isFeatured;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime moderatedAt;
}

package com.tourism.forum.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Dòng tóm tắt 1 bài viết có bình luận — dùng cho group view ở admin comment management.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPostWithCommentsResponse {
    private Integer postId;
    private String title;
    private Integer authorId;
    private Long totalComments;     // tổng comment (chưa xóa)
    private Long pendingComments;   // comment đang PENDING_REVIEW

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastCommentAt;

    // JPQL "new ..." dùng constructor do @AllArgsConstructor sinh ra (6 tham số đúng thứ tự field)
}

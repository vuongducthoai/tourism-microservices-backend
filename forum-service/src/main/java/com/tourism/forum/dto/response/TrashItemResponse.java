package com.tourism.forum.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 1 mục trong thùng rác (bài viết hoặc bình luận đã xóa mềm). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrashItemResponse {
    private String type;        // POST | COMMENT
    private Integer id;
    private String title;       // tiêu đề bài / preview comment
    private Integer authorId;
    private String authorName;
    private Integer deletedBy;
    private String deleteReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deletedAt;
}

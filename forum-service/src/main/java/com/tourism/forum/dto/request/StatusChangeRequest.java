package com.tourism.forum.dto.request;

import com.tourism.forum.entity.ContentStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatusChangeRequest {

    @NotNull(message = "status không được null")
    private ContentStatus status;

    // Lý do admin (tùy chọn) — dùng khi ẩn/từ chối bài
    private String rejectionReason;
}

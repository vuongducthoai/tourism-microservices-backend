package com.tourism.forum.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Trạng thái hạn chế forum của user hiện tại — để FE hiển thị banner cảnh báo.
 */
@Data
@Builder
public class RestrictionStatusResponse {
    private boolean restricted;          // true = đang bị hạn chế
    private boolean permanent;           // true = cấm vĩnh viễn
    private LocalDateTime bannedUntil;   // null nếu vĩnh viễn / không bị cấm
    private String reason;               // lý do (có thể null)
}

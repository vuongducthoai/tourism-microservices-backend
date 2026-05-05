package com.tourism.analytics.dto.feign;

import lombok.*;

/**
 * Response DTO nhận từ GET /api/reviews/chatbot-sync của tour-catalog-service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewSyncDTO {
    private Integer reviewID;
    private Integer tourID;
    private String  tourCode;
    private String  tourName;
    private Integer rating;
    private String  comment;
}

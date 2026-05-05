package com.tourism.tourcatalog.dto.response;

import lombok.*;

/**
 * DTO trả về từ GET /api/reviews/chatbot-sync.
 * Analytics-service dùng để sync review lên Pinecone.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReviewChatbotSyncResponse {
    private Integer reviewID;
    private Integer tourID;
    private String  tourCode;
    private String  tourName;
    private Integer rating;
    private String  comment;
}

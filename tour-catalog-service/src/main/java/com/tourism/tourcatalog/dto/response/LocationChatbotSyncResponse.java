package com.tourism.tourcatalog.dto.response;

import lombok.*;

/**
 * DTO trả về từ GET /api/locations/chatbot-sync.
 * Bao gồm đầy đủ thông tin cần thiết để sync lên Pinecone.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationChatbotSyncResponse {
    private Integer locationID;
    private String  name;
    private String  imageUrl;
    private String  description;
    private String  region;
    private String  airportCode;
    private String  airportName;
}

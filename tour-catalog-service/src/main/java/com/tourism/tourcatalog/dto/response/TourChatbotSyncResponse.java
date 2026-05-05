package com.tourism.tourcatalog.dto.response;

import lombok.*;

import java.util.List;

/**
 * DTO trả về từ GET /api/tours/chatbot-sync.
 * Cung cấp đủ thông tin để analytics-service sync lên Pinecone Vector DB.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TourChatbotSyncResponse {

    private Integer tourID;
    private String  tourCode;
    private String  tourName;
    private String  duration;
    private String  transportation;
    private String  startLocationName;
    private Integer startLocationID;
    private String  endLocationName;
    private Integer endLocationID;
    private String  attractions;
    private String  meals;
    private String  hotel;
    private String  imageUrl;
    private Double  avgRating;
    private Integer reviewCount;

    private List<DepartureSyncResponse> departures;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DepartureSyncResponse {
        private Integer departureID;
        private String  departureDate;      // ISO "yyyy-MM-dd"
        private Integer availableSlots;
        private Double  adultSalePrice;
        private Double  adultOriginalPrice;
        private Double  couponDiscount;     // null nếu không có coupon
        private String  couponCode;
        private String  couponStartDate;
        private String  couponEndDate;
    }
}

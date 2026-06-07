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
    private List<ItineraryDaySyncResponse> itineraryDays;

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
        private List<PricingSyncResponse> pricings;
        private List<TransportSyncResponse> transports;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PricingSyncResponse {
        private String passengerType;
        private String ageDescription;
        private Double salePrice;
        private Double originalPrice;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TransportSyncResponse {
        private String transportType;
        private String vehicleType;
        private String vehicleName;
        private String startPoint;
        private String endPoint;
        private String departTime;
        private String arrivalTime;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItineraryDaySyncResponse {
        private Integer itineraryDayID;
        private Integer dayNumber;
        private String title;
        private String details;
        private String meals;
    }
}

package com.tourism.analytics.dto.feign;

import lombok.*;

import java.util.List;

/**
 * Response DTO nhận từ GET /api/tours/chatbot-sync của tour-catalog-service.
 * Mỗi object là 1 tour active với toàn bộ departures + pricings cần thiết để
 * sync lên Pinecone Vector DB.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TourSyncDTO {

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

    private List<DepartureSyncDTO> departures;
    private List<ItineraryDaySyncDTO> itineraryDays;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartureSyncDTO {
        private Integer departureID;
        private String  departureDate;    // "yyyy-MM-dd"
        private Integer availableSlots;
        private Double  adultSalePrice;
        private Double  adultOriginalPrice;
        // Coupon info (null nếu không có coupon)
        private Double  couponDiscount;
        private String  couponCode;
        private String  couponStartDate;
        private String  couponEndDate;
        private List<PricingSyncDTO> pricings;
        private List<TransportSyncDTO> transports;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PricingSyncDTO {
        private String passengerType;
        private String ageDescription;
        private Double salePrice;
        private Double originalPrice;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransportSyncDTO {
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
    public static class ItineraryDaySyncDTO {
        private Integer itineraryDayID;
        private Integer dayNumber;
        private String title;
        private String details;
        private String meals;
    }
}

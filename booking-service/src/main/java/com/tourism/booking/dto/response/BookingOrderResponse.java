package com.tourism.booking.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
public class BookingOrderResponse {

    private Integer tourId;
    private String tourCode;
    private String tourName;
    private String image;
    private Integer availableSlots;

    private BigDecimal adultPrice;
    private BigDecimal childPrice;
    private BigDecimal toddlerPrice;
    private BigDecimal infantPrice;
    private BigDecimal singleRoomSurcharge;

    private FlightInfo outboundFlight;
    private FlightInfo inboundFlight;

    private CouponInfo departureCoupon;
    private List<CouponInfo> globalCoupons;

    @Data
    @NoArgsConstructor
    public static class FlightInfo {
        private String transportCode;
        private String departTime;
        private String arrivalTime;
        private String vehicleType;
        private String vehicleName;
        private String startPoint;
        private String endPoint;
        private String startPointName;
        private String endPointName;
    }

    @Data
    @NoArgsConstructor
    public static class CouponInfo {
        private String code;
        private Integer discountAmount;
        private String description;
        private BigDecimal minOrderValue;
    }
}

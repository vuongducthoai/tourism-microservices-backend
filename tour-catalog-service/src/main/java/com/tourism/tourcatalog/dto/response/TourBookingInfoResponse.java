package com.tourism.tourcatalog.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
public class TourBookingInfoResponse {

    private Integer tourId;
    private String tourCode;
    private String tourName;
    private String image;
    private Integer availableSlots;
    private Integer couponId;

    private BigDecimal adultPrice;
    private BigDecimal childPrice;
    private BigDecimal toddlerPrice;
    private BigDecimal infantPrice;
    private BigDecimal singleRoomSurcharge;

    private FlightInfo outboundFlight;
    private FlightInfo inboundFlight;

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
}

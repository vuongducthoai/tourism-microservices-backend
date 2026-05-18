package com.tourism.booking.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
public class BookingPaymentDetailResponse {

    private Integer bookingId;
    private String bookingCode;
    private String createdDate;
    private String status;
    private BigDecimal originalPrice;
    private BigDecimal paidAmount;
    private BigDecimal remainingAmount;
    private String paymentDeadline;
    private List<String> appliedCouponCodes;

    private String tourName;
    private String tourCode;
    private String tourImage;
    private String duration;

    private FlightInfo outboundTransport;
    private FlightInfo inboundTransport;

    private List<PassengerInfo> passengers;

    @Data
    @NoArgsConstructor
    public static class FlightInfo {
        private String vehicleType;
        private String departTime;
        private String arrivalTime;
        private String transportCode;
        private String startPoint;
        private String startPointName;
        private String endPoint;
        private String endPointName;
        private String vehicleName;
    }

    @Data
    @NoArgsConstructor
    public static class PassengerInfo {
        private String fullName;
        private String dateOfBirth;
        private String gender;
        private String type;
        private boolean singleRoom;
    }
}

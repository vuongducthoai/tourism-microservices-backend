package com.tourism.analytics.dto.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatbotBookingDetailResponse {
    private Long   bookingId;
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
    private Map<String, Object> outboundTransport;
    private Map<String, Object> inboundTransport;
    private List<PassengerInfo> passengers;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PassengerInfo {
        private String fullName;
        private String gender;
        private String type;
        private String dateOfBirth;
    }
}

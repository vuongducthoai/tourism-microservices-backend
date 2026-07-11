package com.tourism.tourcatalog.dto.response.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminDepartureDetailResponse {
    private Integer departureID;
    private Integer tourId;
    private String tourName;
    private String tourCode;
    private String tourDuration;
    private String departureDate;
    private Integer availableSlots;
    private Integer bookedSlots;
    private String tourGuideInfo;
    private String couponCode;
    private Integer policyTemplateId;
    private String policyTemplateName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Boolean status;
    private List<AdminDeparturePricingItem> pricings;
    private AdminDepartureTransportItem outboundTransport;
    private AdminDepartureTransportItem inboundTransport;
}

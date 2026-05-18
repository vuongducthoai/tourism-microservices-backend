package com.tourism.tourcatalog.dto.request.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateDepartureRequest {
    private Integer tourId;
    private String departureDate;
    private Integer availableSlots;
    private Boolean status;
    private String tourGuideInfo;
    private Integer policyTemplateId;
    private String couponCode;
    private List<DeparturePricingRequest> pricings;
    private DepartureTransportRequest outboundTransport;
    private DepartureTransportRequest inboundTransport;
}

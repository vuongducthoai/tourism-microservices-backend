package com.tourism.tourcatalog.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartureDetailResponse {
    private Integer departureId;
    private String departureDate;
    private Integer availableSlots;
    private List<DeparturePricingResponse> pricings;
    private List<DepartureTransportResponse> transports;
    private String departureCouponCode;
    private BigDecimal departureCouponDiscount;
    private String globalCouponCode;
    private BigDecimal globalCouponDiscount;
    private BigDecimal totalDiscountAmount;
}

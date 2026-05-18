package com.tourism.booking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminCouponResponse {
    private Integer couponId;
    private String couponCode;
    private String description;
    private Integer discountAmount;
    private LocalDateTime startDate;
    private LocalDateTime endDate;
    private Integer usageLimit;
    private Integer usageCount;
    private BigDecimal minOrderValue;
    private Boolean isActive;
    private String couponType;
    private List<DepartureInfo> departureDetails;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DepartureInfo {
        private Integer departureId;
        private String tourCode;
        private String tourName;
        private Integer tourId;
        private String departureDate;
    }
}

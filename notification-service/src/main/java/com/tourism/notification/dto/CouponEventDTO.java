package com.tourism.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponEventDTO {
    private Integer couponId;
    private String couponCode;
    private Integer discountAmount;
    private String couponType;
    private String startDate;
    private String endDate;
    private String description;
    private String eventType;
    private String tourName;
    private String departureDate;
}

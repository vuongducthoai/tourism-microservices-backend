package com.tourism.tourcatalog.feign.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponBriefResponse {
    private Integer couponId;
    private String couponCode;
    private Integer discountAmount;
    private String couponType;
    private Integer departureId;
}

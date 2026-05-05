package com.tourism.analytics.dto.feign;

import lombok.*;

/**
 * Response DTO nhận từ GET /api/bookings/coupons/chatbot-sync của booking-service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponSyncDTO {
    private Integer couponID;
    private String  couponCode;
    private String  description;
    private Integer discountAmount;   // VND
    private String  startDate;
    private String  endDate;
    private Integer usageLimit;
    private Integer usageCount;
    private String  couponType;       // "GLOBAL" | "DEPARTURE"
    private Integer departureId;      // null nếu GLOBAL
}

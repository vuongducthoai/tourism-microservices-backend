package com.tourism.booking.dto.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponChatbotSyncResponse {
    private Integer couponID;
    private String  couponCode;
    private String  description;
    private Integer discountAmount;   // VND
    private String  startDate;        // "yyyy-MM-dd'T'HH:mm:ss"
    private String  endDate;
    private Integer usageLimit;
    private Integer usageCount;
    private String  couponType;       // "GLOBAL" | "DEPARTURE"
    private Integer departureId;      // null nếu là GLOBAL
}

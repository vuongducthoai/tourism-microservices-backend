package com.tourism.booking.dto.response.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourAttentionRawItem {
    private Integer departureId;
    private String tourCode;
    private String tourName;
    private String reason;   // REFUND_REQUEST | LOW_BOOKING
    private String urgency;  // HIGH | MEDIUM | LOW
    private Long count;
}

package com.tourism.analytics.dto.dashboard.feign;

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
    private String reason;
    private String urgency;
    private Long count;
}

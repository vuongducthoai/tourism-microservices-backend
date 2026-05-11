package com.tourism.analytics.dto.dashboard.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotTourRawItem {
    private Integer departureId;
    private String tourCode;
    private String tourName;
    private Long bookingCount;
    private BigDecimal revenue;
}

package com.tourism.analytics.dto.dashboard.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourPerformanceItem {
    private String tourName;
    private Long bookings;
    private Long revenue;
    private Double rating;
}

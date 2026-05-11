package com.tourism.tourcatalog.dto.response.stats;

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
    private Long bookings;    // 0 — booking data ở booking-service
    private Long revenue;     // 0
    private Double rating;
}

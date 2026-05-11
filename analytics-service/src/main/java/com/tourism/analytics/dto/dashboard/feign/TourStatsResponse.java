package com.tourism.analytics.dto.dashboard.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourStatsResponse {
    private Long totalTours;
    private Long activeTours;
    private Long totalDepartures;
    private Long upcomingDepartures;
    private Double averageRating;
    private List<TourPerformanceItem> tourPerformance;

    public static TourStatsResponse empty() {
        return TourStatsResponse.builder()
                .totalTours(0L).activeTours(0L).totalDepartures(0L)
                .upcomingDepartures(0L).averageRating(0.0)
                .tourPerformance(List.of())
                .build();
    }
}

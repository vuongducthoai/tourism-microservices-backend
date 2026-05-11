package com.tourism.iam.dto.response.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyUserGrowthItem {
    private String date;      // yyyy-MM-dd
    private Long newUsers;
    private Long totalUsers;  // cumulative — tính trong analytics-service
}

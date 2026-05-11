package com.tourism.analytics.dto.dashboard.feign;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyUserGrowthItem {
    private String date;
    private Long newUsers;
    private Long totalUsers;
}

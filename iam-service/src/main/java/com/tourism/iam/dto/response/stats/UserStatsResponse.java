package com.tourism.iam.dto.response.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStatsResponse {
    private Long totalUsers;
    private Long activeUsers;
    private Long lockedUsers;
    private Long newUsersToday;
    private Long newUsersThisWeek;
    private Long newUsersThisMonth;
    private Long newUsersLastMonth;
    private Long baseUserCountBefore30Days;  // để tính cumulative total trong chart
    private List<DailyUserGrowthItem> dailyGrowth;
    private List<RecentUserItem> recentUsers;

    public static UserStatsResponse empty() {
        return UserStatsResponse.builder()
                .totalUsers(0L)
                .activeUsers(0L)
                .lockedUsers(0L)
                .newUsersToday(0L)
                .newUsersThisWeek(0L)
                .newUsersThisMonth(0L)
                .newUsersLastMonth(0L)
                .baseUserCountBefore30Days(0L)
                .dailyGrowth(List.of())
                .recentUsers(List.of())
                .build();
    }
}

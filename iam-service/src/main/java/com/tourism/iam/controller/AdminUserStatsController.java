package com.tourism.iam.controller;

import com.tourism.iam.dto.response.stats.DailyUserGrowthItem;
import com.tourism.iam.dto.response.stats.RecentUserItem;
import com.tourism.iam.dto.response.stats.UserStatsResponse;
import com.tourism.iam.entity.Role;
import com.tourism.iam.entity.User;
import com.tourism.iam.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin Stats", description = "Thống kê nội bộ dùng cho Dashboard — gọi bởi analytics-service")
public class AdminUserStatsController {

    private final UserRepository userRepository;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @GetMapping("/stats")
    @Operation(summary = "Lấy thống kê người dùng cho Dashboard",
               description = "Trả về counts và daily growth chart — CUSTOMER only")
    @ApiResponse(responseCode = "200", description = "User stats")
    public ResponseEntity<UserStatsResponse> getUserStats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        log.info("Fetching user stats for dashboard from={} to={}", from, to);

        LocalDate today = LocalDate.now();
        LocalDate rangeEnd = (to != null) ? LocalDate.parse(to, DATE_FMT) : today;
        LocalDate rangeStart = (from != null) ? LocalDate.parse(from, DATE_FMT) : rangeEnd.minusDays(30);
        long daysInRange = ChronoUnit.DAYS.between(rangeStart, rangeEnd.plusDays(1));
        LocalDate prevStart = rangeStart.minusDays(daysInRange);

        LocalDateTime rangeStartDT = rangeStart.atStartOfDay();
        LocalDateTime rangeEndDT = rangeEnd.plusDays(1).atStartOfDay();
        LocalDateTime prevStartDT = prevStart.atStartOfDay();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime weekAgoStart = today.minusDays(7).atStartOfDay();

        Long totalUsers = safe(userRepository.countByRole(Role.CUSTOMER));
        Long activeUsers = safe(userRepository.countByStatusAndRole(true, Role.CUSTOMER));
        Long lockedUsers = safe(userRepository.countByStatusAndRole(false, Role.CUSTOMER));

        Long newUsersToday = safe(userRepository.countByRoleAndCreatedAtBetween(
                Role.CUSTOMER, todayStart, tomorrowStart));
        Long newUsersThisWeek = safe(userRepository.countByRoleAndCreatedAtBetween(
                Role.CUSTOMER, weekAgoStart, tomorrowStart));
        Long newUsersThisMonth = safe(userRepository.countByRoleAndCreatedAtBetween(
                Role.CUSTOMER, rangeStartDT, rangeEndDT));
        Long newUsersLastMonth = safe(userRepository.countByRoleAndCreatedAtBetween(
                Role.CUSTOMER, prevStartDT, rangeStartDT));

        Long baseCount = safe(userRepository.countByRoleAndCreatedAtBefore(
                Role.CUSTOMER, rangeStartDT));

        List<DailyUserGrowthItem> dailyGrowth = buildDailyGrowth(rangeStartDT, rangeEndDT, baseCount);

        List<RecentUserItem> recentUsers = userRepository
                .findTop5ByRoleOrderByCreatedAtDesc(Role.CUSTOMER)
                .stream()
                .map(u -> RecentUserItem.builder()
                        .fullName(u.getFullName())
                        .email(u.getEmail())
                        .createdAt(u.getCreatedAt() != null ? u.getCreatedAt().toString() : "")
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(UserStatsResponse.builder()
                .totalUsers(totalUsers)
                .activeUsers(activeUsers)
                .lockedUsers(lockedUsers)
                .newUsersToday(newUsersToday)
                .newUsersThisWeek(newUsersThisWeek)
                .newUsersThisMonth(newUsersThisMonth)
                .newUsersLastMonth(newUsersLastMonth)
                .baseUserCountBefore30Days(baseCount)
                .dailyGrowth(dailyGrowth)
                .recentUsers(recentUsers)
                .build());
    }

    private List<DailyUserGrowthItem> buildDailyGrowth(LocalDateTime start,
                                                         LocalDateTime end,
                                                         Long baseCount) {
        List<Object[]> rows = userRepository.getDailyNewUsersCounts(start, end, Role.CUSTOMER);

        // Map date string → newUsers count
        Map<String, Long> dayMap = rows.stream().collect(
                Collectors.toMap(
                        r -> r[0].toString(),
                        r -> ((Number) r[1]).longValue()
                )
        );

        List<DailyUserGrowthItem> result = new ArrayList<>();
        long cumulative = baseCount;
        LocalDate cur = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();

        while (!cur.isAfter(endDate.minusDays(1))) {
            String dateStr = cur.format(DATE_FMT);
            long newU = dayMap.getOrDefault(dateStr, 0L);
            cumulative += newU;
            result.add(DailyUserGrowthItem.builder()
                    .date(dateStr)
                    .newUsers(newU)
                    .totalUsers(cumulative)
                    .build());
            cur = cur.plusDays(1);
        }
        return result;
    }

    private Long safe(Long val) {
        return val != null ? val : 0L;
    }
}

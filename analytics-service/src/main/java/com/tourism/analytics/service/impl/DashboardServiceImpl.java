package com.tourism.analytics.service.impl;

import com.tourism.analytics.dto.dashboard.DashboardStatsDTO;
import com.tourism.analytics.dto.dashboard.feign.*;
import com.tourism.analytics.feign.BookingFeignClient;
import com.tourism.analytics.feign.IamFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import com.tourism.analytics.service.DashboardService;
import com.tourism.analytics.service.GeminiAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * DashboardServiceImpl — tổng hợp dữ liệu từ 3 service (iam, booking, tour-catalog)
 * thông qua Feign client, build DashboardStatsDTO đầy đủ.
 * Port từ monolith Tourism_Backend/DashboardServiceImpl.java.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardServiceImpl implements DashboardService {

    private final IamFeignClient iamFeignClient;
    private final BookingFeignClient bookingFeignClient;
    private final TourCatalogFeignClient tourCatalogFeignClient;
    private final GeminiAIService geminiAIService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public DashboardStatsDTO getDashboardStatistics(LocalDate from, LocalDate to) {
        String fromStr = from.format(DATE_FMT);
        String toStr = to.format(DATE_FMT);
        UserStatsResponse ur = fetchUserStats(fromStr, toStr);
        BookingStatsResponse br = fetchBookingStats(fromStr, toStr);
        TourStatsResponse tr = fetchTourStats(fromStr, toStr);

        return DashboardStatsDTO.builder()
                .userStats(buildUserStats(ur))
                .revenueStats(buildRevenueStats(br))
                .bookingStats(buildBookingStats(br))
                .tourStats(buildTourStats(tr, br))
                .recentActivities(buildRecentActivities(br, ur))
                .chartsData(buildChartsData(br, ur, tr))
                .aiAnalysis(DashboardStatsDTO.AIAnalysis.builder()
                        .summary("").insights(List.of()).predictions(List.of()).recommendations(List.of())
                        .build())
                .build();
    }

    @Override
    public DashboardStatsDTO.AIAnalysis getDashboardAIAnalysis(LocalDate from, LocalDate to, String mode) {
        String fromStr = from.format(DATE_FMT);
        String toStr = to.format(DATE_FMT);
        UserStatsResponse ur = fetchUserStats(fromStr, toStr);
        BookingStatsResponse br = fetchBookingStats(fromStr, toStr);
        TourStatsResponse tr = fetchTourStats(fromStr, toStr);

        String context = buildAIContext(ur, br, tr, from, to, mode);

        return geminiAIService.generateFullAnalysis(context);
    }

    // ════════════════════════════════════════════════════════════════
    // Feign calls with fallback
    // ════════════════════════════════════════════════════════════════

    private UserStatsResponse fetchUserStats(String from, String to) {
        try { return iamFeignClient.getUserStats(from, to); }
        catch (Exception e) { log.error("Failed to fetch user stats: {}", e.getMessage()); return UserStatsResponse.empty(); }
    }

    private BookingStatsResponse fetchBookingStats(String from, String to) {
        try { return bookingFeignClient.getBookingStats(from, to); }
        catch (Exception e) { log.error("Failed to fetch booking stats: {}", e.getMessage()); return BookingStatsResponse.empty(); }
    }

    private TourStatsResponse fetchTourStats(String from, String to) {
        try { return tourCatalogFeignClient.getTourStats(from, to); }
        catch (Exception e) { log.error("Failed to fetch tour stats: {}", e.getMessage()); return TourStatsResponse.empty(); }
    }

    // ════════════════════════════════════════════════════════════════
    // Build sections
    // ════════════════════════════════════════════════════════════════

    private DashboardStatsDTO.UserStats buildUserStats(UserStatsResponse ur) {
        double growthRate = calculateGrowthRate(
                ur.getNewUsersThisMonth(), ur.getNewUsersLastMonth());

        List<DashboardStatsDTO.DailyUserGrowth> dailyGrowth = ur.getDailyGrowth() == null ? List.of() :
                ur.getDailyGrowth().stream()
                        .map(d -> DashboardStatsDTO.DailyUserGrowth.builder()
                                .date(d.getDate()).newUsers(d.getNewUsers()).totalUsers(d.getTotalUsers())
                                .build())
                        .collect(Collectors.toList());

        return DashboardStatsDTO.UserStats.builder()
                .totalUsers(safe(ur.getTotalUsers()))
                .activeUsers(safe(ur.getActiveUsers()))
                .lockedUsers(safe(ur.getLockedUsers()))
                .newUsersToday(safe(ur.getNewUsersToday()))
                .newUsersThisWeek(safe(ur.getNewUsersThisWeek()))
                .newUsersThisMonth(safe(ur.getNewUsersThisMonth()))
                .userGrowthRate(growthRate)
                .dailyGrowth(dailyGrowth)
                .build();
    }

    private DashboardStatsDTO.RevenueStats buildRevenueStats(BookingStatsResponse br) {
        double growthRate = calculateGrowthRate(
                br.getThisMonthRevenue(), br.getLastMonthRevenue());

        List<DashboardStatsDTO.DailyRevenue> dailyRevenue = br.getDailyRevenue() == null ? List.of() :
                br.getDailyRevenue().stream()
                        .map(d -> DashboardStatsDTO.DailyRevenue.builder()
                                .date(d.getDate()).revenue(safeDecimal(d.getRevenue())).bookingCount(safe(d.getBookingCount()))
                                .build())
                        .collect(Collectors.toList());

        return DashboardStatsDTO.RevenueStats.builder()
                .totalRevenue(safeDecimal(br.getTotalRevenue()))
                .pendingConfirmation(safeDecimal(br.getPendingConfirmRevenue()))
                .pendingPayment(safeDecimal(br.getPendingPayRevenue()))
                .pendingRefund(safeDecimal(br.getPendingRefundRevenue()))
                .cancelledRevenue(safeDecimal(br.getCancelledRevenue()))
                .todayRevenue(safeDecimal(br.getTodayRevenue()))
                .thisWeekRevenue(safeDecimal(br.getThisWeekRevenue()))
                .thisMonthRevenue(safeDecimal(br.getThisMonthRevenue()))
                .lastMonthRevenue(safeDecimal(br.getLastMonthRevenue()))
                .revenueGrowthRate(growthRate)
                .dailyRevenue(dailyRevenue)
                .revenueByTour(buildRevenueByTour(br))
                .build();
    }

    private DashboardStatsDTO.BookingStats buildBookingStats(BookingStatsResponse br) {
        long paid = safe(br.getPaidBookings());
        long total = safe(br.getTotalBookings());
        double conversionRate = total > 0 ? Math.round(paid * 100.0 / total * 100.0) / 100.0 : 0.0;

        List<DashboardStatsDTO.BookingStatusCount> statusDist = br.getStatusDistribution() == null ? List.of() :
                br.getStatusDistribution().stream()
                        .map(s -> DashboardStatsDTO.BookingStatusCount.builder()
                                .status(s.getStatus()).count(safe(s.getCount())).revenue(safeDecimal(s.getRevenue()))
                                .build())
                        .collect(Collectors.toList());

        return DashboardStatsDTO.BookingStats.builder()
                .totalBookings(total).paidBookings(paid)
                .pendingConfirmation(safe(br.getPendingConfirmation()))
                .pendingPayment(safe(br.getPendingPayment()))
                .pendingRefund(safe(br.getPendingRefund()))
                .cancelledBookings(safe(br.getCancelledBookings()))
                .todayBookings(safe(br.getTodayBookings()))
                .thisWeekBookings(safe(br.getThisWeekBookings()))
                .conversionRate(conversionRate)
                .statusDistribution(statusDist)
                .build();
    }

    private DashboardStatsDTO.TourStats buildTourStats(TourStatsResponse tr, BookingStatsResponse br) {
        List<DashboardStatsDTO.HotTour> hotTours = br.getHotTours() == null ? List.of() :
                br.getHotTours().stream()
                        .map(h -> DashboardStatsDTO.HotTour.builder()
                                .tourCode(h.getTourCode()).tourName(h.getTourName())
                                .bookingCount(safe(h.getBookingCount())).revenue(safeDecimal(h.getRevenue()))
                                .averageRating(0.0).build())
                        .collect(Collectors.toList());

        List<DashboardStatsDTO.TourNeedingAttention> attention = br.getToursNeedingAttention() == null ? List.of() :
                br.getToursNeedingAttention().stream()
                        .map(a -> DashboardStatsDTO.TourNeedingAttention.builder()
                                .tourCode(a.getTourCode()).tourName(a.getTourName())
                                .reason(a.getReason()).urgency(a.getUrgency()).build())
                        .collect(Collectors.toList());

        return DashboardStatsDTO.TourStats.builder()
                .totalTours(safe(tr.getTotalTours()))
                .activeTours(safe(tr.getActiveTours()))
                .totalDepartures(safe(tr.getTotalDepartures()))
                .upcomingDepartures(safe(tr.getUpcomingDepartures()))
                .averageRating(tr.getAverageRating() != null ? tr.getAverageRating() : 0.0)
                .hotTours(hotTours)
                .toursNeedingAttention(attention)
                .build();
    }

    private List<DashboardStatsDTO.RecentActivity> buildRecentActivities(
            BookingStatsResponse br, UserStatsResponse ur) {
        List<DashboardStatsDTO.RecentActivity> activities = new ArrayList<>();

        if (br.getRecentPendingConfirmation() != null) {
            br.getRecentPendingConfirmation().stream()
                    .map(b -> DashboardStatsDTO.RecentActivity.builder()
                            .type(b.getType()).description(b.getDescription())
                            .timestamp(b.getCreatedAt()).severity(b.getSeverity())
                            .relatedCode(b.getBookingCode()).build())
                    .forEach(activities::add);
        }
        if (br.getRecentRefundRequests() != null) {
            br.getRecentRefundRequests().stream()
                    .map(b -> DashboardStatsDTO.RecentActivity.builder()
                            .type(b.getType()).description(b.getDescription())
                            .timestamp(b.getCreatedAt()).severity(b.getSeverity())
                            .relatedCode(b.getBookingCode()).build())
                    .forEach(activities::add);
        }
        if (ur.getRecentUsers() != null) {
            ur.getRecentUsers().stream()
                    .map(u -> DashboardStatsDTO.RecentActivity.builder()
                            .type("NEW_USER").description("Khách hàng mới: " + u.getFullName() + " (" + u.getEmail() + ")")
                            .timestamp(u.getCreatedAt()).severity("INFO").relatedCode(u.getEmail())
                            .build())
                    .forEach(activities::add);
        }

        // Sort by timestamp DESC and limit 10
        activities.sort(Comparator.comparing(
                a -> a.getTimestamp() != null ? a.getTimestamp() : "",
                Comparator.reverseOrder()));
        return activities.stream().limit(10).collect(Collectors.toList());
    }

    private DashboardStatsDTO.ChartsData buildChartsData(
            BookingStatsResponse br, UserStatsResponse ur, TourStatsResponse tr) {

        List<DashboardStatsDTO.DailyRevenue> revenueChart = br.getDailyRevenue() == null ? List.of() :
                br.getDailyRevenue().stream()
                        .map(d -> DashboardStatsDTO.DailyRevenue.builder()
                                .date(d.getDate()).revenue(safeDecimal(d.getRevenue())).bookingCount(safe(d.getBookingCount()))
                                .build())
                        .collect(Collectors.toList());

        List<DashboardStatsDTO.DailyUserGrowth> userGrowthChart = ur.getDailyGrowth() == null ? List.of() :
                ur.getDailyGrowth().stream()
                        .map(d -> DashboardStatsDTO.DailyUserGrowth.builder()
                                .date(d.getDate()).newUsers(d.getNewUsers()).totalUsers(d.getTotalUsers())
                                .build())
                        .collect(Collectors.toList());

        List<DashboardStatsDTO.BookingStatusCount> bookingStatusChart = br.getStatusDistribution() == null ? List.of() :
                br.getStatusDistribution().stream()
                        .map(s -> DashboardStatsDTO.BookingStatusCount.builder()
                                .status(s.getStatus()).count(safe(s.getCount())).revenue(safeDecimal(s.getRevenue()))
                                .build())
                        .collect(Collectors.toList());

        List<DashboardStatsDTO.TourPerformance> tourPerformanceChart = tr.getTourPerformance() == null ? List.of() :
                tr.getTourPerformance().stream()
                        .map(t -> DashboardStatsDTO.TourPerformance.builder()
                                .tourName(t.getTourName()).bookings(safe(t.getBookings()))
                                .revenue(t.getRevenue() != null ? BigDecimal.valueOf(t.getRevenue()) : BigDecimal.ZERO)
                                .rating(t.getRating() != null ? t.getRating() : 0.0)
                                .build())
                        .collect(Collectors.toList());

        return DashboardStatsDTO.ChartsData.builder()
                .revenueChart(revenueChart)
                .userGrowthChart(userGrowthChart)
                .bookingStatusChart(bookingStatusChart)
                .tourPerformanceChart(tourPerformanceChart)
                .build();
    }

    // ════════════════════════════════════════════════════════════════
    // AI Context builder
    // ════════════════════════════════════════════════════════════════

    private String buildAIContext(UserStatsResponse ur, BookingStatsResponse br, TourStatsResponse tr,
                                   LocalDate from, LocalDate to, String mode) {
        String period = "Kỳ phân tích: " + from.format(DATE_FMT) + " đến " + to.format(DATE_FMT);
        String focusNote = switch (mode) {
            case "REVENUE" -> "Tập trung phân tích doanh thu và tài chính.";
            case "USERS" -> "Tập trung phân tích người dùng và tăng trưởng.";
            case "TOURS" -> "Tập trung phân tích tour và chất lượng dịch vụ.";
            default -> "Phân tích tổng quan toàn bộ hệ thống.";
        };
        return String.format(
                "%s. %s\n" +
                        "User Statistics (Customers Only): Total=%d, Active=%d, NewThisMonth=%d, NewLastMonth=%d, Growth=%.2f%%.\n" +
                        "Revenue: Total=%.0f VNĐ, ThisPeriod=%.0f VNĐ, PreviousPeriod=%.0f VNĐ, Today=%.0f VNĐ, Growth=%.2f%%.\n" +
                        "Booking: Total=%d, Paid=%d, PendingConfirm=%d, PendingRefund=%d, Cancelled=%d, ConversionRate=%.2f%%.\n" +
                        "Tours: Total=%d, Active=%d, Departures=%d, Upcoming=%d, AvgRating=%.2f.",
                period, focusNote,
                safe(ur.getTotalUsers()), safe(ur.getActiveUsers()),
                safe(ur.getNewUsersThisMonth()), safe(ur.getNewUsersLastMonth()),
                calculateGrowthRate(ur.getNewUsersThisMonth(), ur.getNewUsersLastMonth()),
                safeDecimal(br.getTotalRevenue()).doubleValue(),
                safeDecimal(br.getThisMonthRevenue()).doubleValue(),
                safeDecimal(br.getLastMonthRevenue()).doubleValue(),
                safeDecimal(br.getTodayRevenue()).doubleValue(),
                calculateGrowthRate(br.getThisMonthRevenue(), br.getLastMonthRevenue()),
                safe(br.getTotalBookings()), safe(br.getPaidBookings()),
                safe(br.getPendingConfirmation()), safe(br.getPendingRefund()), safe(br.getCancelledBookings()),
                safe(br.getTotalBookings()) > 0 ? safe(br.getPaidBookings()) * 100.0 / safe(br.getTotalBookings()) : 0.0,
                safe(tr.getTotalTours()), safe(tr.getActiveTours()),
                safe(tr.getTotalDepartures()), safe(tr.getUpcomingDepartures()),
                tr.getAverageRating() != null ? tr.getAverageRating() : 0.0
        );
    }

    private Map<String, BigDecimal> buildRevenueByTour(BookingStatsResponse br) {
        if (br.getHotTours() == null) return Map.of();
        return br.getHotTours().stream()
                .filter(h -> h.getTourName() != null)
                .collect(Collectors.toMap(
                        HotTourRawItem::getTourName,
                        h -> safeDecimal(h.getRevenue()),
                        (a, b) -> a.add(b)
                ));
    }

    // ════════════════════════════════════════════════════════════════
    // Utilities
    // ════════════════════════════════════════════════════════════════

    private double calculateGrowthRate(Long current, Long previous) {
        long cur = current != null ? current : 0L;
        long prev = previous != null ? previous : 0L;
        if (prev == 0) return cur > 0 ? 100.0 : 0.0;
        return Math.round((cur - prev) * 100.0 / prev * 100.0) / 100.0;
    }

    private double calculateGrowthRate(BigDecimal current, BigDecimal previous) {
        BigDecimal cur = current != null ? current : BigDecimal.ZERO;
        BigDecimal prev = previous != null ? previous : BigDecimal.ZERO;
        if (prev.compareTo(BigDecimal.ZERO) == 0)
            return cur.compareTo(BigDecimal.ZERO) > 0 ? 100.0 : 0.0;
        return cur.subtract(prev)
                .multiply(BigDecimal.valueOf(100))
                .divide(prev, 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private Long safe(Long val) { return val != null ? val : 0L; }
    private BigDecimal safeDecimal(BigDecimal val) { return val != null ? val : BigDecimal.ZERO; }
}

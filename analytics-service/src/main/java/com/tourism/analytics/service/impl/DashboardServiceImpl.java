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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
        DashboardStatsDTO.AiEvidenceDashboard evidenceDashboard = buildAiEvidenceDashboard(ur, br, tr);

        return DashboardStatsDTO.builder()
                .userStats(buildUserStats(ur))
                .revenueStats(buildRevenueStats(br))
                .bookingStats(buildBookingStats(br))
                .tourStats(buildTourStats(tr, br))
                .recentActivities(buildRecentActivities(br, ur))
                .chartsData(buildChartsData(br, ur, tr))
                .aiAnalysis(DashboardStatsDTO.AIAnalysis.builder()
                        .summary("").insights(List.of()).predictions(List.of()).recommendations(List.of())
                        .periodFrom(fromStr).periodTo(toStr).mode("OVERVIEW")
                        .generatedAt(LocalDateTime.now().toString())
                        .verificationSummary("Bảng kiểm chứng số liệu đã sẵn sàng. Chưa có nội dung AI.")
                        .aiEvidenceDashboard(evidenceDashboard)
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
        DashboardStatsDTO.AiEvidenceDashboard evidenceDashboard = buildAiEvidenceDashboard(ur, br, tr);

        String context = buildAIContext(ur, br, tr, from, to, mode, evidenceDashboard);

        DashboardStatsDTO.AIAnalysis generated = geminiAIService.generateFullAnalysis(context);
        return enrichAiAnalysis(generated, evidenceDashboard, fromStr, toStr, mode);
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
                                   LocalDate from, LocalDate to, String mode,
                                   DashboardStatsDTO.AiEvidenceDashboard evidenceDashboard) {
        long daysInRange = ChronoUnit.DAYS.between(from, to.plusDays(1));
        LocalDate previousFrom = from.minusDays(daysInRange);
        LocalDate previousTo = from.minusDays(1);
        String period = "Giai đoạn đang xem: " + from.format(DATE_FMT) + " đến " + to.format(DATE_FMT) + ". " +
                "Giai đoạn so sánh: " + previousFrom.format(DATE_FMT) + " đến " + previousTo.format(DATE_FMT) + " " +
                "(cùng " + daysInRange + " ngày ngay trước giai đoạn đang xem). ";
        String focusNote = switch (mode) {
            case "REVENUE" -> "Tập trung phân tích doanh thu, dòng tiền và rủi ro thất thoát.";
            case "USERS" -> "Tập trung phân tích khách hàng, khách mới và khả năng chăm sóc lại.";
            case "TOURS" -> "Tập trung phân tích tour, lịch khởi hành và chất lượng dịch vụ.";
            default -> "Phân tích tổng quan tình hình kinh doanh.";
        };
        String baseContext = String.format(
                "%s. %s\n" +
                        "Yêu cầu cách viết: dùng ngôn ngữ nghiệp vụ dễ hiểu cho quản trị viên, không dùng thuật ngữ code. " +
                        "Không viết mơ hồ 'kỳ này/kỳ trước' nếu không giải thích; hãy viết 'giai đoạn đang xem' và 'giai đoạn so sánh'. " +
                        "Mỗi kết luận phải nêu con số chứng minh chính.\n" +
                        "Khách hàng: tổng khách=%d, khách đang hoạt động=%d, khách mới trong giai đoạn đang xem=%d, khách mới trong giai đoạn so sánh=%d, tỷ lệ tăng/giảm khách mới=%.2f%%.\n" +
                        "Doanh thu: tổng doanh thu đã ghi nhận=%.0f VND, doanh thu trong giai đoạn đang xem=%.0f VND, doanh thu trong giai đoạn so sánh=%.0f VND, doanh thu hôm nay=%.0f VND, tỷ lệ tăng/giảm doanh thu=%.2f%%.\n" +
                        "Đặt tour: tổng booking=%d, booking đã thanh toán=%d, booking chờ xác nhận=%d, booking chờ hoàn tiền=%d, booking đã hủy=%d, tỷ lệ chuyển đổi booking=%.2f%%.\n" +
                        "Tour: tổng tour=%d, tour đang bán=%d, tổng lịch khởi hành=%d, lịch sắp khởi hành=%d, điểm đánh giá trung bình=%.2f/5.",
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
        return baseContext + "\n\n" + buildEvidenceContext(evidenceDashboard);
    }

    private String buildEvidenceContext(DashboardStatsDTO.AiEvidenceDashboard evidenceDashboard) {
        StringBuilder sb = new StringBuilder();
        sb.append("AI_EVIDENCE_METRICS. Chi duoc dung cac metricKey sau, khong tu tao so lieu moi. ")
                .append("Khi dua ra nhan dinh, du bao hoac khuyen nghi, phai gan usedMetricKeys voi cac metricKey phu hop:\n");
        if (evidenceDashboard == null || evidenceDashboard.getGroups() == null) return sb.toString();
        for (DashboardStatsDTO.AiEvidenceGroup group : evidenceDashboard.getGroups()) {
            sb.append("- ").append(group.getGroupLabel()).append(":\n");
            for (DashboardStatsDTO.AiEvidenceMetric metric : safeList(group.getMetrics())) {
                sb.append("  * metricKey=").append(metric.getMetricKey())
                        .append(", ten_so_lieu=").append(metric.getLabel())
                        .append(", ket_qua_giai_doan_dang_xem=").append(metric.getCurrentValue())
                        .append(", ket_qua_giai_doan_so_sanh=").append(metric.getPreviousValue())
                        .append(", chenh_lech=").append(metric.getChangeValue())
                        .append(", cach_tinh=").append(metric.getFormula())
                        .append(", nguon_du_lieu=").append(metric.getSourceService())
                        .append(", trang_thai_kiem_chung=").append(metric.getDataQuality())
                        .append("\n");
            }
        }
        return sb.toString();
    }

    private DashboardStatsDTO.AiEvidenceDashboard buildAiEvidenceDashboard(
            UserStatsResponse ur, BookingStatsResponse br, TourStatsResponse tr) {

        long totalBookings = safe(br.getTotalBookings());
        long paidBookings = safe(br.getPaidBookings());
        long cancelledBookings = safe(br.getCancelledBookings());
        double conversionRate = percent(paidBookings, totalBookings);
        double cancellationRate = percent(cancelledBookings, totalBookings);
        String bookingQuality = sourceQualityForBooking(br);
        String userQuality = sourceQualityForUser(ur);
        String tourQuality = sourceQualityForTour(tr);

        BigDecimal thisMonthRevenue = safeDecimal(br.getThisMonthRevenue());
        BigDecimal lastMonthRevenue = safeDecimal(br.getLastMonthRevenue());
        double revenueGrowth = calculateGrowthRate(br.getThisMonthRevenue(), br.getLastMonthRevenue());
        double userGrowth = calculateGrowthRate(ur.getNewUsersThisMonth(), ur.getNewUsersLastMonth());

        List<DashboardStatsDTO.AiEvidenceGroup> groups = List.of(
                evidenceGroup("revenue", "Doanh thu", List.of(
                        evidenceMetric("revenue.total", "Tổng doanh thu", money(br.getTotalRevenue()), "-", "-",
                                null, "Cộng tiền từ các đơn đã thu tiền: đã thanh toán, đã đi tour chờ đánh giá, và đã đánh giá.", "Dữ liệu đặt tour",
                                "/api/admin/bookings/stats", bookingQuality, "Số liệu gốc từ hệ thống, không do AI tự tạo."),
                        evidenceMetric("revenue.thisPeriod", "Doanh thu trong giai đoạn đang xem", money(thisMonthRevenue), money(lastMonthRevenue),
                                money(thisMonthRevenue.subtract(lastMonthRevenue)), revenueGrowth,
                                "Lấy doanh thu của giai đoạn đang xem trừ doanh thu của giai đoạn so sánh, sau đó chia cho doanh thu giai đoạn so sánh.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats",
                                previousPeriodQuality(bookingQuality, br.getLastMonthRevenue(), br.getThisMonthRevenue()),
                                "Cho biết trong khoảng ngày admin đang xem, doanh thu đang tăng hay giảm so với giai đoạn liền trước."),
                        evidenceMetric("revenue.growthRate", "Tỷ lệ tăng trưởng doanh thu", percentText(revenueGrowth), "-",
                                percentText(revenueGrowth), revenueGrowth,
                                "(Doanh thu giai đoạn đang xem - doanh thu giai đoạn so sánh) / doanh thu giai đoạn so sánh x 100.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats",
                                previousPeriodQuality(bookingQuality, br.getLastMonthRevenue(), br.getThisMonthRevenue()),
                                "Cho biết doanh thu tăng hoặc giảm bao nhiêu phần trăm so với giai đoạn liền trước."),
                        evidenceMetric("revenue.pendingPayment", "Doanh thu tiềm năng chưa thu", money(br.getPendingPayRevenue()), "-",
                                "-", null, "Cộng giá trị các booking chưa thanh toán.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Đây là tiền còn treo ở bước thanh toán, chưa phải doanh thu chắc chắn đã thu."),
                        evidenceMetric("revenue.pendingRefund", "Số tiền có thể phải hoàn", money(br.getPendingRefundRevenue()), "-",
                                "-", null, "Cộng giá trị các booking đang chờ hoàn tiền.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Dùng để theo dõi khoản tiền có khả năng phải trả lại cho khách."),
                        evidenceMetric("revenue.cancelled", "Doanh thu tiềm năng mất do hủy", money(br.getCancelledRevenue()), "-",
                                "-", null, "Cộng giá trị các booking đã bị hủy.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Dùng để ước lượng phần doanh thu không còn cơ hội ghi nhận vì booking đã bị hủy.")
                )),
                evidenceGroup("users", "Người dùng", List.of(
                        evidenceMetric("user.total", "Tổng khách hàng", number(ur.getTotalUsers()), "-", "-",
                                null, "Đếm toàn bộ tài khoản khách hàng.", "Dữ liệu khách hàng",
                                "/api/admin/users/stats", userQuality, "Nguồn dữ liệu khách hàng trong hệ thống."),
                        evidenceMetric("user.active", "Khách hàng đang hoạt động", number(ur.getActiveUsers()), "-", "-",
                                null, "Đếm khách hàng đang có trạng thái hoạt động.", "Dữ liệu khách hàng",
                                "/api/admin/users/stats", userQuality, "Dùng để đánh giá tệp khách có thể chăm sóc."),
                        evidenceMetric("user.newThisMonth", "Khách mới trong giai đoạn đang xem", number(ur.getNewUsersThisMonth()),
                                number(ur.getNewUsersLastMonth()),
                                number(safe(ur.getNewUsersThisMonth()) - safe(ur.getNewUsersLastMonth())),
                                userGrowth, "(Khách mới giai đoạn đang xem - khách mới giai đoạn so sánh) / khách mới giai đoạn so sánh x 100.",
                                "Dữ liệu khách hàng", "/api/admin/users/stats",
                                previousPeriodQuality(userQuality, ur.getNewUsersLastMonth(), ur.getNewUsersThisMonth()),
                                "Cho biết trong khoảng ngày admin đang xem có bao nhiêu khách hàng mới."),
                        evidenceMetric("user.newLastMonth", "Khách mới trong giai đoạn so sánh", number(ur.getNewUsersLastMonth()), "-",
                                "-", null, "Đếm khách mới trong giai đoạn liền trước có cùng độ dài.",
                                "Dữ liệu khách hàng", "/api/admin/users/stats", userQuality,
                                "Đây là mốc nền để biết khách mới hiện tại tăng hay giảm."),
                        evidenceMetric("user.growthRate", "Tỷ lệ tăng trưởng khách mới", percentText(userGrowth), "-",
                                percentText(userGrowth), userGrowth,
                                "(Khách mới giai đoạn đang xem - khách mới giai đoạn so sánh) / khách mới giai đoạn so sánh x 100.",
                                "Dữ liệu khách hàng", "/api/admin/users/stats",
                                previousPeriodQuality(userQuality, ur.getNewUsersLastMonth(), ur.getNewUsersThisMonth()),
                                "Cho biết khả năng thu hút khách mới đang tốt lên hay xấu đi."),
                        evidenceMetric("user.locked", "Tài khoản bị khóa", number(ur.getLockedUsers()), "-", "-",
                                null, "Đếm khách hàng đang bị khóa tài khoản.", "Dữ liệu khách hàng",
                                "/api/admin/users/stats", userQuality, "Cần theo dõi nếu ảnh hưởng trải nghiệm khách.")
                )),
                evidenceGroup("booking", "Booking", List.of(
                        evidenceMetric("booking.total", "Tổng booking trong giai đoạn đang xem", number(br.getTotalBookings()), "-", "-",
                                null, "Đếm toàn bộ booking phát sinh trong giai đoạn đang xem.", "Dữ liệu đặt tour",
                                "/api/admin/bookings/stats", bookingQuality, "Mẫu số để tính các tỷ lệ booking."),
                        evidenceMetric("booking.paid", "Booking đã thanh toán", number(br.getPaidBookings()), "-", "-",
                                null, "Đếm các đơn đã thu tiền (đã thanh toán, chờ đánh giá, đã đánh giá).", "Dữ liệu đặt tour",
                                "/api/admin/bookings/stats", bookingQuality, "Dùng để tính tỷ lệ chuyển đổi."),
                        evidenceMetric("booking.cancelled", "Booking đã hủy", number(br.getCancelledBookings()), "-", "-",
                                null, "Đếm booking đã bị hủy.", "Dữ liệu đặt tour",
                                "/api/admin/bookings/stats", bookingQuality, "Dùng để kiểm chứng nhận định về hủy đơn."),
                        evidenceMetric("booking.cancellationRate", "Tỷ lệ hủy booking", percentText(cancellationRate), "-",
                                percentText(cancellationRate), cancellationRate,
                                "Booking đã hủy / tổng booking x 100.", "Dữ liệu đặt tour",
                                "/api/admin/bookings/stats", bookingQuality, "Số booking bị hủy chia cho tổng booking."),
                        evidenceMetric("booking.pendingConfirmation", "Booking chờ xác nhận", number(br.getPendingConfirmation()), "-",
                                "-", null, "Đếm booking đang chờ nhân viên xác nhận.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Liên quan tốc độ tư vấn và xác nhận."),
                        evidenceMetric("booking.pendingPayment", "Booking chờ thanh toán", number(br.getPendingPayment()), "-",
                                "-", null, "Đếm booking đang chờ khách thanh toán.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Liên quan khả năng chuyển đổi thành doanh thu."),
                        evidenceMetric("booking.pendingRefund", "Booking chờ hoàn tiền", number(br.getPendingRefund()), "-",
                                "-", null, "Đếm booking đang chờ xử lý hoàn tiền.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Liên quan rủi ro vận hành và chăm sóc khách hàng."),
                        evidenceMetric("booking.conversionRate", "Tỷ lệ chuyển đổi booking", percentText(conversionRate), "-",
                                percentText(conversionRate), conversionRate,
                                "Booking đã thanh toán / tổng booking x 100.", "Dữ liệu đặt tour",
                                "/api/admin/bookings/stats", bookingQuality, "Số booking đã thanh toán chia cho tổng booking.")
                )),
                evidenceGroup("tour", "Tour", List.of(
                        evidenceMetric("tour.total", "Tổng tour", number(tr.getTotalTours()), "-", "-",
                                null, "Đếm toàn bộ tour trong hệ thống.", "Dữ liệu tour",
                                "/api/admin/tours/stats", tourQuality, "Tổng số tour trong hệ thống."),
                        evidenceMetric("tour.active", "Tour đang hoạt động", number(tr.getActiveTours()), "-", "-",
                                null, "Đếm tour đang mở bán hoặc đang hoạt động.", "Dữ liệu tour",
                                "/api/admin/tours/stats", tourQuality, "Nguồn cung tour hiện tại."),
                        evidenceMetric("tour.departures", "Tổng lịch khởi hành", number(tr.getTotalDepartures()), "-", "-",
                                null, "Đếm toàn bộ lịch khởi hành.", "Dữ liệu tour",
                                "/api/admin/tours/stats", tourQuality, "Tổng lịch khởi hành được thống kê."),
                        evidenceMetric("tour.upcomingDepartures", "Tour sắp khởi hành", number(tr.getUpcomingDepartures()), "-",
                                "-", null, "Đếm lịch khởi hành sắp diễn ra.", "Dữ liệu tour",
                                "/api/admin/tours/stats", tourQuality, "Dùng để kiểm chứng năng lực vận hành sắp tới."),
                        evidenceMetric("tour.averageRating", "Đánh giá trung bình", decimal(tr.getAverageRating()) + "/5", "-",
                                "-", null, "Tính trung bình điểm đánh giá tour.", "Dữ liệu tour",
                                "/api/admin/tours/stats", tourQuality, "Dùng để kiểm chứng chất lượng dịch vụ."),
                        evidenceMetric("tour.hotTop3", "Tour bán chạy trong giai đoạn đang xem", String.valueOf(safeList(br.getHotTours()).size()), "-",
                                "-", null, "Đếm tour có ít nhất một booking đã thanh toán trong giai đoạn đang xem; hệ thống xếp tour theo số booking đã thanh toán, nếu bằng nhau thì ưu tiên doanh thu cao hơn.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Cho biết trong giai đoạn admin đang xem có bao nhiêu tour thật sự tạo ra đơn đã thanh toán."),
                        evidenceMetric("tour.needingAttention", "Tour cần xử lý hiện tại", String.valueOf(safeList(br.getToursNeedingAttention()).size()), "-",
                                "-", null, "Đếm tour đang có booking chờ hoàn tiền hoặc dấu hiệu vận hành cần xử lý; hiện hệ thống ưu tiên nhóm có yêu cầu hoàn tiền.",
                                "Dữ liệu đặt tour", "/api/admin/bookings/stats", bookingQuality,
                                "Cho biết có bao nhiêu tour admin nên mở ra kiểm tra ngay vì đang có vấn đề vận hành hoặc hoàn tiền.")
                ))
        );

        return DashboardStatsDTO.AiEvidenceDashboard.builder().groups(groups).build();
    }

    private DashboardStatsDTO.AIAnalysis enrichAiAnalysis(
            DashboardStatsDTO.AIAnalysis analysis,
            DashboardStatsDTO.AiEvidenceDashboard evidenceDashboard,
            String fromStr,
            String toStr,
            String mode) {
        DashboardStatsDTO.AIAnalysis result = analysis != null ? analysis : DashboardStatsDTO.AIAnalysis.builder().build();
        result.setInsights(new ArrayList<>(safeList(result.getInsights())));
        result.setPredictions(new ArrayList<>(safeList(result.getPredictions())));
        result.setRecommendations(new ArrayList<>(safeList(result.getRecommendations())));
        result.setPeriodFrom(fromStr);
        result.setPeriodTo(toStr);
        result.setMode(mode);
        result.setGeneratedAt(LocalDateTime.now().toString());
        result.setAiEvidenceDashboard(evidenceDashboard);

        Map<String, DashboardStatsDTO.AiEvidenceMetric> metricMap = collectMetricMap(evidenceDashboard);
        int verified = 0;
        int limited = 0;
        int unverified = 0;

        for (int i = 0; i < result.getInsights().size(); i++) {
            DashboardStatsDTO.Insight item = result.getInsights().get(i);
            List<String> keys = normalizeMetricKeys(item.getUsedMetricKeys(), item.getTitle() + " " + item.getDescription(), metricMap);
            item.setUsedMetricKeys(keys);
            item.setVerificationStatus(resolveVerificationStatus(keys, metricMap));
            item.setConfidenceReason(confidenceReason(keys, metricMap));
            addUsageToMetrics(keys, metricMap, "Nhận định #" + (i + 1));
            if ("VERIFIED".equals(item.getVerificationStatus())) verified++;
            else if ("LIMITED".equals(item.getVerificationStatus())) limited++;
            else unverified++;
        }

        for (int i = 0; i < result.getPredictions().size(); i++) {
            DashboardStatsDTO.Prediction item = result.getPredictions().get(i);
            List<String> keys = normalizeMetricKeys(item.getUsedMetricKeys(), item.getMetric() + " " + item.getPrediction(), metricMap);
            item.setUsedMetricKeys(keys);
            item.setVerificationStatus(resolveVerificationStatus(keys, metricMap));
            item.setConfidenceReason(confidenceReason(keys, metricMap));
            addUsageToMetrics(keys, metricMap, "Dự báo #" + (i + 1));
            if ("VERIFIED".equals(item.getVerificationStatus())) verified++;
            else if ("LIMITED".equals(item.getVerificationStatus())) limited++;
            else unverified++;
        }

        for (int i = 0; i < result.getRecommendations().size(); i++) {
            DashboardStatsDTO.Recommendation item = result.getRecommendations().get(i);
            List<String> keys = normalizeMetricKeys(item.getUsedMetricKeys(), item.getTitle() + " " + item.getDescription() + " " + item.getAction(), metricMap);
            item.setUsedMetricKeys(keys);
            item.setVerificationStatus(resolveVerificationStatus(keys, metricMap));
            item.setConfidenceReason(confidenceReason(keys, metricMap));
            addUsageToMetrics(keys, metricMap, "Khuyến nghị #" + (i + 1));
            if ("VERIFIED".equals(item.getVerificationStatus())) verified++;
            else if ("LIMITED".equals(item.getVerificationStatus())) limited++;
            else unverified++;
        }

        result.setVerificationSummary(String.format(
                "Đã kiểm chứng %d nội dung AI: %d đã xác minh, %d cần đọc kèm ghi chú, %d chưa có dẫn chứng.",
                verified + limited + unverified, verified, limited, unverified));
        return result;
    }

    private DashboardStatsDTO.AiEvidenceGroup evidenceGroup(
            String groupKey, String groupLabel, List<DashboardStatsDTO.AiEvidenceMetric> metrics) {
        return DashboardStatsDTO.AiEvidenceGroup.builder()
                .groupKey(groupKey)
                .groupLabel(groupLabel)
                .metrics(metrics)
                .build();
    }

    private DashboardStatsDTO.AiEvidenceMetric evidenceMetric(
            String metricKey, String label, String currentValue, String previousValue, String changeValue,
            Double changePercent, String formula, String sourceService, String sourceEndpoint,
            String dataQuality, String note) {
        return DashboardStatsDTO.AiEvidenceMetric.builder()
                .metricKey(metricKey)
                .label(label)
                .currentValue(currentValue)
                .previousValue(previousValue)
                .changeValue(changeValue)
                .changePercent(changePercent)
                .formula(formula)
                .sourceService(sourceService)
                .sourceEndpoint(sourceEndpoint)
                .usedByAiItems(new ArrayList<>())
                .dataQuality(dataQuality)
                .note(note)
                .build();
    }

    private Map<String, DashboardStatsDTO.AiEvidenceMetric> collectMetricMap(
            DashboardStatsDTO.AiEvidenceDashboard evidenceDashboard) {
        Map<String, DashboardStatsDTO.AiEvidenceMetric> map = new LinkedHashMap<>();
        if (evidenceDashboard == null) return map;
        for (DashboardStatsDTO.AiEvidenceGroup group : safeList(evidenceDashboard.getGroups())) {
            for (DashboardStatsDTO.AiEvidenceMetric metric : safeList(group.getMetrics())) {
                map.put(metric.getMetricKey(), metric);
            }
        }
        return map;
    }

    private List<String> normalizeMetricKeys(List<String> keys, String text, Map<String, DashboardStatsDTO.AiEvidenceMetric> metricMap) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String key : safeList(keys)) {
            if (key != null && metricMap.containsKey(key)) normalized.add(key);
        }
        if (normalized.isEmpty()) normalized.addAll(inferMetricKeys(text, metricMap));
        return new ArrayList<>(normalized);
    }

    private List<String> inferMetricKeys(String text, Map<String, DashboardStatsDTO.AiEvidenceMetric> metricMap) {
        String source = text == null ? "" : text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (containsAny(source, "nguồn thu", "khách mới", "khách hàng mới", "người dùng mới",
                "thu hút khách", "hủy", "chuyển đổi", "thanh toán", "khởi hành", "đánh giá")) {
            if (containsAny(source, "nguồn thu", "doanh thu")) {
                keys.add("revenue.total");
                keys.add("revenue.growthRate");
            }
            if (containsAny(source, "khách mới", "khách hàng mới", "người dùng mới", "thu hút khách")) {
                keys.add("user.newThisMonth");
                keys.add("user.growthRate");
            }
            if (containsAny(source, "hủy")) {
                keys.add("booking.cancelled");
                keys.add("booking.cancellationRate");
            }
            if (containsAny(source, "chuyển đổi", "thanh toán")) {
                keys.add("booking.conversionRate");
                keys.add("booking.pendingPayment");
            }
            if (containsAny(source, "khởi hành", "đánh giá")) {
                keys.add("tour.upcomingDepartures");
                keys.add("tour.averageRating");
            }
        }
        if (containsAny(source, "doanh thu", "revenue", "nguon thu")) {
            keys.add("revenue.total");
            keys.add("revenue.growthRate");
        }
        if (containsAny(source, "khach moi", "khách mới", "khach hang moi", "khách hàng mới", "nguoi dung moi", "thu hut khach")) {
            keys.add("user.newThisMonth");
            keys.add("user.growthRate");
        }
        if (containsAny(source, "huy", "hủy", "cancel")) {
            keys.add("booking.cancelled");
            keys.add("booking.cancellationRate");
        }
        if (containsAny(source, "chuyen doi", "chuyển đổi", "conversion", "thanh toan", "thanh toán")) {
            keys.add("booking.conversionRate");
            keys.add("booking.pendingPayment");
        }
        if (containsAny(source, "tour", "khoi hanh", "khởi hành", "danh gia", "đánh giá", "rating")) {
            keys.add("tour.upcomingDepartures");
            keys.add("tour.averageRating");
        }
        return keys.stream().filter(metricMap::containsKey).limit(3).collect(Collectors.toList());
    }

    private boolean containsAny(String source, String... keywords) {
        for (String keyword : keywords) {
            if (source.contains(keyword)) return true;
        }
        return false;
    }

    private String resolveVerificationStatus(List<String> keys, Map<String, DashboardStatsDTO.AiEvidenceMetric> metricMap) {
        if (keys == null || keys.isEmpty()) return "UNVERIFIED";
        boolean hasLimited = false;
        for (String key : keys) {
            DashboardStatsDTO.AiEvidenceMetric metric = metricMap.get(key);
            if (metric == null) return "UNVERIFIED";
            if (!"VERIFIED".equals(metric.getDataQuality())) hasLimited = true;
        }
        return hasLimited ? "LIMITED" : "VERIFIED";
    }

    private String confidenceReason(List<String> keys, Map<String, DashboardStatsDTO.AiEvidenceMetric> metricMap) {
        if (keys == null || keys.isEmpty()) return "AI chua co metric doi chieu hop le.";
        String labels = keys.stream()
                .map(metricMap::get)
                .filter(Objects::nonNull)
                .map(DashboardStatsDTO.AiEvidenceMetric::getLabel)
                .collect(Collectors.joining(", "));
        return "Dua tren " + keys.size() + " chi so doi chieu: " + labels + ".";
    }

    private void addUsageToMetrics(List<String> keys, Map<String, DashboardStatsDTO.AiEvidenceMetric> metricMap, String usageLabel) {
        for (String key : safeList(keys)) {
            DashboardStatsDTO.AiEvidenceMetric metric = metricMap.get(key);
            if (metric == null) continue;
            if (metric.getUsedByAiItems() == null) metric.setUsedByAiItems(new ArrayList<>());
            if (!metric.getUsedByAiItems().contains(usageLabel)) metric.getUsedByAiItems().add(usageLabel);
        }
    }

    private double percent(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        return Math.round(numerator * 10000.0 / denominator) / 100.0;
    }

    private String sourceQualityForBooking(BookingStatsResponse br) {
        boolean emptySnapshot = safe(br.getTotalBookings()) == 0
                && safeDecimal(br.getTotalRevenue()).compareTo(BigDecimal.ZERO) == 0
                && safeList(br.getStatusDistribution()).isEmpty();
        return emptySnapshot ? "FALLBACK" : "VERIFIED";
    }

    private String sourceQualityForUser(UserStatsResponse ur) {
        boolean emptySnapshot = safe(ur.getTotalUsers()) == 0
                && safe(ur.getNewUsersThisMonth()) == 0
                && safeList(ur.getDailyGrowth()).isEmpty();
        return emptySnapshot ? "FALLBACK" : "VERIFIED";
    }

    private String sourceQualityForTour(TourStatsResponse tr) {
        boolean emptySnapshot = safe(tr.getTotalTours()) == 0
                && safe(tr.getTotalDepartures()) == 0
                && safeList(tr.getTourPerformance()).isEmpty();
        return emptySnapshot ? "FALLBACK" : "VERIFIED";
    }

    private String previousPeriodQuality(String sourceQuality, Number previous, Number current) {
        if (!"VERIFIED".equals(sourceQuality)) return sourceQuality;
        double prev = previous == null ? 0.0 : previous.doubleValue();
        double cur = current == null ? 0.0 : current.doubleValue();
        return prev == 0.0 && cur > 0.0 ? "LIMITED" : "VERIFIED";
    }

    private String previousPeriodQuality(String sourceQuality, BigDecimal previous, BigDecimal current) {
        if (!"VERIFIED".equals(sourceQuality)) return sourceQuality;
        BigDecimal prev = safeDecimal(previous);
        BigDecimal cur = safeDecimal(current);
        return prev.compareTo(BigDecimal.ZERO) == 0 && cur.compareTo(BigDecimal.ZERO) > 0 ? "LIMITED" : "VERIFIED";
    }

    private String number(Long value) {
        return String.valueOf(safe(value));
    }

    private String number(long value) {
        return String.valueOf(value);
    }

    private String money(BigDecimal value) {
        return safeDecimal(value).setScale(0, RoundingMode.HALF_UP).toPlainString() + " VND";
    }

    private String decimal(Double value) {
        return String.format(Locale.US, "%.2f", value != null ? value : 0.0);
    }

    private String percentText(Double value) {
        return String.format(Locale.US, "%.2f%%", value != null ? value : 0.0);
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
    private <T> List<T> safeList(List<T> val) { return val != null ? val : List.of(); }
}

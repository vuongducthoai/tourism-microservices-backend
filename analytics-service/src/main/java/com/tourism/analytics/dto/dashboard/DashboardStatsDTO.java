package com.tourism.analytics.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Response DTO tổng hợp cho Admin Dashboard.
 * Port từ monolith Tourism_Backend/DashboardStatsDTO.java — giữ nguyên cấu trúc.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {

    private UserStats userStats;
    private RevenueStats revenueStats;
    private BookingStats bookingStats;
    private TourStats tourStats;
    private List<RecentActivity> recentActivities;
    private AIAnalysis aiAnalysis;
    private ChartsData chartsData;

    // ══════════════════════════════════════════════
    // USER STATS
    // ══════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class UserStats {
        private Long totalUsers;
        private Long activeUsers;
        private Long lockedUsers;
        private Long newUsersToday;
        private Long newUsersThisWeek;
        private Long newUsersThisMonth;
        private Double userGrowthRate;
        private List<DailyUserGrowth> dailyGrowth;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DailyUserGrowth {
        private String date;
        private Long newUsers;
        private Long totalUsers;
    }

    // ══════════════════════════════════════════════
    // REVENUE STATS
    // ══════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RevenueStats {
        private BigDecimal totalRevenue;
        private BigDecimal pendingConfirmation;
        private BigDecimal pendingPayment;
        private BigDecimal pendingRefund;
        private BigDecimal cancelledRevenue;
        private BigDecimal todayRevenue;
        private BigDecimal thisWeekRevenue;
        private BigDecimal thisMonthRevenue;
        private BigDecimal lastMonthRevenue;
        private Double revenueGrowthRate;
        private List<DailyRevenue> dailyRevenue;
        private Map<String, BigDecimal> revenueByTour;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class DailyRevenue {
        private String date;
        private BigDecimal revenue;
        private Long bookingCount;
    }

    // ══════════════════════════════════════════════
    // BOOKING STATS
    // ══════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BookingStats {
        private Long totalBookings;
        private Long paidBookings;
        private Long pendingConfirmation;
        private Long pendingPayment;
        private Long pendingRefund;
        private Long cancelledBookings;
        private Long todayBookings;
        private Long thisWeekBookings;
        private Double conversionRate;
        private List<BookingStatusCount> statusDistribution;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class BookingStatusCount {
        private String status;
        private Long count;
        private BigDecimal revenue;
    }

    // ══════════════════════════════════════════════
    // TOUR STATS
    // ══════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TourStats {
        private Long totalTours;
        private Long activeTours;
        private Long totalDepartures;
        private Long upcomingDepartures;
        private List<HotTour> hotTours;
        private List<TourNeedingAttention> toursNeedingAttention;
        private Double averageRating;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class HotTour {
        private Integer tourId;
        private String tourCode;
        private String tourName;
        private Long bookingCount;
        private BigDecimal revenue;
        private Double averageRating;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TourNeedingAttention {
        private Integer tourId;
        private String tourCode;
        private String tourName;
        private String reason;
        private String urgency;
    }

    // ══════════════════════════════════════════════
    // RECENT ACTIVITIES
    // ══════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RecentActivity {
        private String type;
        private String description;
        private String timestamp;
        private String severity;
        private String relatedCode;
    }

    // ══════════════════════════════════════════════
    // AI ANALYSIS
    // ══════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AIAnalysis {
        private String summary;
        private List<Insight> insights;
        private List<Prediction> predictions;
        private List<Recommendation> recommendations;
        private String periodFrom;
        private String periodTo;
        private String mode;
        private String generatedAt;
        private String verificationSummary;
        private AiEvidenceDashboard aiEvidenceDashboard;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Insight {
        private String title;
        private String description;
        private String type;     // POSITIVE | NEUTRAL | NEGATIVE
        private Integer priority; // 1-5
        private List<String> usedMetricKeys;
        private String verificationStatus; // VERIFIED | LIMITED | UNVERIFIED
        private String confidenceReason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Prediction {
        private String metric;
        private String prediction;
        private Integer confidence; // 0-100
        private String timeframe;
        private List<String> usedMetricKeys;
        private String verificationStatus; // VERIFIED | LIMITED | UNVERIFIED
        private String confidenceReason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Recommendation {
        private String title;
        private String description;
        private String action;
        private Integer impact; // 1-5
        private List<String> usedMetricKeys;
        private String verificationStatus; // VERIFIED | LIMITED | UNVERIFIED
        private String confidenceReason;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AiEvidenceDashboard {
        private List<AiEvidenceGroup> groups;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AiEvidenceGroup {
        private String groupKey;
        private String groupLabel;
        private List<AiEvidenceMetric> metrics;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class AiEvidenceMetric {
        private String metricKey;
        private String label;
        private String currentValue;
        private String previousValue;
        private String changeValue;
        private Double changePercent;
        private String formula;
        private String sourceService;
        private String sourceEndpoint;
        private List<String> usedByAiItems;
        private String dataQuality; // VERIFIED | LIMITED | FALLBACK
        private String note;
    }

    // ══════════════════════════════════════════════
    // CHARTS DATA
    // ══════════════════════════════════════════════
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ChartsData {
        private List<DailyRevenue> revenueChart;
        private List<DailyUserGrowth> userGrowthChart;
        private List<BookingStatusCount> bookingStatusChart;
        private List<TourPerformance> tourPerformanceChart;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class TourPerformance {
        private String tourName;
        private Long bookings;
        private BigDecimal revenue;
        private Double rating;
    }
}

package com.tourism.booking.dto.response.stats;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingStatsResponse {

    // ── Booking counts ──
    private Long totalBookings;
    private Long paidBookings;
    private Long pendingConfirmation;
    private Long pendingPayment;
    private Long pendingRefund;
    private Long cancelledBookings;
    private Long todayBookings;
    private Long thisWeekBookings;

    // ── Revenue ──
    private BigDecimal totalRevenue;
    private BigDecimal pendingConfirmRevenue;
    private BigDecimal pendingPayRevenue;
    private BigDecimal pendingRefundRevenue;
    private BigDecimal cancelledRevenue;
    private BigDecimal todayRevenue;
    private BigDecimal thisWeekRevenue;
    private BigDecimal thisMonthRevenue;
    private BigDecimal lastMonthRevenue;

    // ── Charts ──
    private List<DailyRevenueItem> dailyRevenue;
    private List<BookingStatusCountItem> statusDistribution;

    // ── Top tours & attention ──
    private List<HotTourRawItem> hotTours;
    private List<TourAttentionRawItem> toursNeedingAttention;

    // ── Recent activities ──
    private List<RecentBookingItem> recentPendingConfirmation;
    private List<RecentBookingItem> recentRefundRequests;

    public static BookingStatsResponse empty() {
        return BookingStatsResponse.builder()
                .totalBookings(0L).paidBookings(0L).pendingConfirmation(0L)
                .pendingPayment(0L).pendingRefund(0L).cancelledBookings(0L)
                .todayBookings(0L).thisWeekBookings(0L)
                .totalRevenue(BigDecimal.ZERO).pendingConfirmRevenue(BigDecimal.ZERO)
                .pendingPayRevenue(BigDecimal.ZERO).pendingRefundRevenue(BigDecimal.ZERO)
                .cancelledRevenue(BigDecimal.ZERO).todayRevenue(BigDecimal.ZERO)
                .thisWeekRevenue(BigDecimal.ZERO).thisMonthRevenue(BigDecimal.ZERO)
                .lastMonthRevenue(BigDecimal.ZERO)
                .dailyRevenue(List.of()).statusDistribution(List.of())
                .hotTours(List.of()).toursNeedingAttention(List.of())
                .recentPendingConfirmation(List.of()).recentRefundRequests(List.of())
                .build();
    }
}

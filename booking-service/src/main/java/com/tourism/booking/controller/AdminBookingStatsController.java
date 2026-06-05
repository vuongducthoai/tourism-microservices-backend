package com.tourism.booking.controller;

import com.tourism.booking.dto.response.stats.*;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.BookingStatus;
import com.tourism.booking.feign.TourCatalogFeignClient;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.repository.BookingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/bookings")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin Stats", description = "Thống kê booking/doanh thu nội bộ dùng cho Dashboard")
public class AdminBookingStatsController {

    private final BookingRepository bookingRepository;
    private final TourCatalogFeignClient tourCatalogFeignClient;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @GetMapping("/stats")
    @Operation(summary = "Lấy thống kê booking & doanh thu cho Dashboard")
    @ApiResponse(responseCode = "200", description = "Booking stats")
    public ResponseEntity<BookingStatsResponse> getBookingStats(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String from,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String to) {
        log.info("Fetching booking stats for dashboard from={} to={}", from, to);

        LocalDate today = LocalDate.now();
        LocalDate rangeEnd = (to != null) ? LocalDate.parse(to, DATE_FMT) : today;
        LocalDate rangeStart = (from != null) ? LocalDate.parse(from, DATE_FMT) : rangeEnd.minusDays(30);
        long daysInRange = ChronoUnit.DAYS.between(rangeStart, rangeEnd.plusDays(1));
        LocalDate prevStart = rangeStart.minusDays(daysInRange);

        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime tomorrowStart = today.plusDays(1).atStartOfDay();
        LocalDateTime weekAgoStart = today.minusDays(7).atStartOfDay();
        LocalDateTime rangeStartDT = rangeStart.atStartOfDay();
        LocalDateTime rangeEndDT = rangeEnd.plusDays(1).atStartOfDay();
        LocalDateTime prevStartDT = prevStart.atStartOfDay();

        // ── Booking counts in selected dashboard range ──
        Long totalBookings = safe(bookingRepository.countActiveBookingsBetween(rangeStartDT, rangeEndDT));
        Long paidBookings = safe(bookingRepository.countByBookingStatusBetween(BookingStatus.PAID, rangeStartDT, rangeEndDT));
        Long pendingConfirmation = safe(bookingRepository.countByBookingStatusBetween(BookingStatus.PENDING_CONFIRMATION, rangeStartDT, rangeEndDT));
        Long pendingPayment = safe(bookingRepository.countByBookingStatusBetween(BookingStatus.PENDING_PAYMENT, rangeStartDT, rangeEndDT));
        Long pendingRefund = safe(bookingRepository.countByBookingStatusBetween(BookingStatus.PENDING_REFUND, rangeStartDT, rangeEndDT));
        Long cancelledBookings = safe(bookingRepository.countByBookingStatusBetween(BookingStatus.CANCELLED, rangeStartDT, rangeEndDT));
        Long todayBookings = safe(bookingRepository.countByBookingDateBetween(todayStart, tomorrowStart));
        Long thisWeekBookings = safe(bookingRepository.countByBookingDateBetween(weekAgoStart, tomorrowStart));

        // ── Revenue ──
        BigDecimal totalRevenue = safeDecimal(bookingRepository.sumTotalPriceByStatus(BookingStatus.PAID));
        BigDecimal pendingConfirmRevenue = safeDecimal(bookingRepository.sumTotalPriceByStatusBetween(BookingStatus.PENDING_CONFIRMATION, rangeStartDT, rangeEndDT));
        BigDecimal pendingPayRevenue = safeDecimal(bookingRepository.sumTotalPriceByStatusBetween(BookingStatus.PENDING_PAYMENT, rangeStartDT, rangeEndDT));
        BigDecimal pendingRefundRevenue = safeDecimal(bookingRepository.sumTotalPriceByStatusBetween(BookingStatus.PENDING_REFUND, rangeStartDT, rangeEndDT));
        BigDecimal cancelledRevenue = safeDecimal(bookingRepository.sumTotalPriceByStatusBetween(BookingStatus.CANCELLED, rangeStartDT, rangeEndDT));
        BigDecimal todayRevenue = safeDecimal(bookingRepository.sumRevenueByDateAndStatus(todayStart, tomorrowStart, BookingStatus.PAID));
        BigDecimal thisWeekRevenue = safeDecimal(bookingRepository.sumRevenueByDateAndStatus(weekAgoStart, tomorrowStart, BookingStatus.PAID));
        BigDecimal thisMonthRevenue = safeDecimal(bookingRepository.sumRevenueByDateAndStatus(rangeStartDT, rangeEndDT, BookingStatus.PAID));
        BigDecimal lastMonthRevenue = safeDecimal(bookingRepository.sumRevenueByDateAndStatus(prevStartDT, rangeStartDT, BookingStatus.PAID));

        // ── Daily revenue chart (selected range) ──
        List<DailyRevenueItem> dailyRevenue = buildDailyRevenue(rangeStartDT, rangeEndDT);

        // ── Status distribution 
        List<BookingStatusCountItem> statusDistribution = buildStatusDistribution(rangeStartDT, rangeEndDT);

        // ── Hot tours in selected range (top 5 PAID)
        List<HotTourRawItem> hotTours = buildHotTours(rangeStartDT, rangeEndDT);

        // ── Tours needing attention ──
        List<TourAttentionRawItem> toursNeedingAttention = buildToursNeedingAttention();

        // ── Recent activities ──
        List<RecentBookingItem> recentPending = buildRecentBookings(BookingStatus.PENDING_CONFIRMATION, "BOOKING", "WARNING");
        List<RecentBookingItem> recentRefunds = buildRecentBookings(BookingStatus.PENDING_REFUND, "REFUND", "URGENT");

        return ResponseEntity.ok(BookingStatsResponse.builder()
                .totalBookings(totalBookings)
                .paidBookings(paidBookings)
                .pendingConfirmation(pendingConfirmation)
                .pendingPayment(pendingPayment)
                .pendingRefund(pendingRefund)
                .cancelledBookings(cancelledBookings)
                .todayBookings(todayBookings)
                .thisWeekBookings(thisWeekBookings)
                .totalRevenue(totalRevenue)
                .pendingConfirmRevenue(pendingConfirmRevenue)
                .pendingPayRevenue(pendingPayRevenue)
                .pendingRefundRevenue(pendingRefundRevenue)
                .cancelledRevenue(cancelledRevenue)
                .todayRevenue(todayRevenue)
                .thisWeekRevenue(thisWeekRevenue)
                .thisMonthRevenue(thisMonthRevenue)
                .lastMonthRevenue(lastMonthRevenue)
                .dailyRevenue(dailyRevenue)
                .statusDistribution(statusDistribution)
                .hotTours(hotTours)
                .toursNeedingAttention(toursNeedingAttention)
                .recentPendingConfirmation(recentPending)
                .recentRefundRequests(recentRefunds)
                .build());
    }

    private List<DailyRevenueItem> buildDailyRevenue(LocalDateTime start, LocalDateTime end) {
        List<Object[]> rows = bookingRepository.getDailyRevenueCounts(start, end, BookingStatus.PAID);
        Map<String, Object[]> dayMap = rows.stream()
                .collect(Collectors.toMap(r -> r[0].toString(), r -> r));

        List<DailyRevenueItem> result = new ArrayList<>();
        LocalDate cur = start.toLocalDate();
        LocalDate endDate = end.toLocalDate();
        while (!cur.isAfter(endDate.minusDays(1))) {
            String dateStr = cur.format(DATE_FMT);
            Object[] row = dayMap.get(dateStr);
            BigDecimal rev = row != null ? safeDecimal((BigDecimal) row[1]) : BigDecimal.ZERO;
            Long cnt = row != null ? ((Number) row[2]).longValue() : 0L;
            result.add(DailyRevenueItem.builder().date(dateStr).revenue(rev).bookingCount(cnt).build());
            cur = cur.plusDays(1);
        }
        return result;
    }

    private List<BookingStatusCountItem> buildStatusDistribution(LocalDateTime start, LocalDateTime end) {
        return bookingRepository.getBookingStatusDistributionBetween(start, end).stream()
                .map(r -> BookingStatusCountItem.builder()
                        .status((String) r[0])
                        .count(((Number) r[1]).longValue())
                        .revenue(safeDecimal(r[2] instanceof BigDecimal ? (BigDecimal) r[2] : null))
                        .build())
                .collect(Collectors.toList());
    }

    private List<HotTourRawItem> buildHotTours(LocalDateTime start, LocalDateTime end) {
        List<Object[]> rows = bookingRepository.getTopDeparturesByBookingCountBetween(
                BookingStatus.PAID, start, end, PageRequest.of(0, 10));

        List<HotTourRawItem> result = new ArrayList<>();
        for (Object[] row : rows) {
            Integer departureId = ((Number) row[0]).intValue();
            Long cnt = ((Number) row[1]).longValue();
            BigDecimal rev = safeDecimal(row[2] instanceof BigDecimal ? (BigDecimal) row[2] : null);

            String tourCode = "N/A";
            String tourName = "N/A";
            try {
                DepartureInfoResponse info = tourCatalogFeignClient.getDepartureInfo(departureId);
                if (info != null) {
                    tourCode = info.getTourCode() != null ? info.getTourCode() : "N/A";
                    tourName = info.getTourName() != null ? info.getTourName() : "N/A";
                }
            } catch (Exception e) {
                log.warn("Cannot fetch departure {} from tour-catalog: {}", departureId, e.getMessage());
            }

            // Deduplicate by tourCode — keep highest count
            String finalTourCode = tourCode;
            boolean exists = result.stream().anyMatch(h -> h.getTourCode().equals(finalTourCode));
            if (!exists && result.size() < 5) {
                result.add(HotTourRawItem.builder()
                        .departureId(departureId)
                        .tourCode(tourCode)
                        .tourName(tourName)
                        .bookingCount(cnt)
                        .revenue(rev)
                        .build());
            }
        }
        return result;
    }

    private List<TourAttentionRawItem> buildToursNeedingAttention() {
        List<TourAttentionRawItem> result = new ArrayList<>();

        // REFUND_REQUEST tours
        List<Object[]> refundRows = bookingRepository.getTopDeparturesByStatus(
                BookingStatus.PENDING_REFUND, PageRequest.of(0, 3));
        for (Object[] row : refundRows) {
            Integer departureId = ((Number) row[0]).intValue();
            Long cnt = ((Number) row[1]).longValue();
            String tourCode = "N/A";
            String tourName = "N/A";
            try {
                DepartureInfoResponse info = tourCatalogFeignClient.getDepartureInfo(departureId);
                if (info != null) {
                    tourCode = info.getTourCode() != null ? info.getTourCode() : "N/A";
                    tourName = info.getTourName() != null ? info.getTourName() : "N/A";
                }
            } catch (Exception e) {
                log.warn("Cannot fetch departure {} info: {}", departureId, e.getMessage());
            }
            result.add(TourAttentionRawItem.builder()
                    .departureId(departureId).tourCode(tourCode).tourName(tourName)
                    .reason("REFUND_REQUEST").urgency("HIGH").count(cnt)
                    .build());
        }

        return result;
    }

    private List<RecentBookingItem> buildRecentBookings(BookingStatus status, String type, String severity) {
        return bookingRepository.findTop5ByBookingStatusOrderByCreatedAtDesc(status)
                .stream()
                .map(b -> RecentBookingItem.builder()
                        .bookingCode(b.getBookingCode())
                        .description(buildDescription(b, type))
                        .createdAt(b.getCreatedAt() != null ? b.getCreatedAt().toString() : "")
                        .type(type)
                        .severity(severity)
                        .build())
                .collect(Collectors.toList());
    }

    private String buildDescription(Booking b, String type) {
        if ("REFUND".equals(type)) {
            return "Yêu cầu hoàn tiền: " + b.getBookingCode();
        }
        return "Booking " + b.getBookingCode() + " chờ xác nhận";
    }

    private Long safe(Long val) { return val != null ? val : 0L; }
    private BigDecimal safeDecimal(Object val) {
        if (val instanceof BigDecimal) return (BigDecimal) val;
        return BigDecimal.ZERO;
    }
}

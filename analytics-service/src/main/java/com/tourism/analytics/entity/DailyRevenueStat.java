package com.tourism.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Materialized view: thống kê doanh thu theo ngày.
 * Được populate qua events từ Booking Service & Payment Service.
 */
@Entity
@Table(name = "daily_revenue_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DailyRevenueStat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_date", nullable = false, unique = true)
    private LocalDate statDate;

    private Long totalBookings = 0L;
    private Long paidBookings = 0L;
    private Long cancelledBookings = 0L;

    private BigDecimal totalRevenue = BigDecimal.ZERO;
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    private Long newUsers = 0L;
}

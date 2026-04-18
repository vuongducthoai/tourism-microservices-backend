package com.tourism.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Materialized view: thống kê hiệu suất mỗi tour.
 * Được populate qua events từ Tour Catalog, Booking, Review.
 */
@Entity
@Table(name = "tour_performance_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TourPerformanceStat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tour_id", nullable = false, unique = true)
    private Integer tourId;

    private String tourCode;
    private String tourName;

    private Long totalBookings = 0L;
    private Long totalPassengers = 0L;
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    private Double averageRating = 0.0;
    private Long totalReviews = 0L;

    private Long totalDepartures = 0L;
    private Long activeDepartures = 0L;
}

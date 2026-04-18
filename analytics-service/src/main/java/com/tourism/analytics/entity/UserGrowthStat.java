package com.tourism.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * Materialized view: thống kê tăng trưởng user theo ngày.
 * Được populate qua events từ IAM Service.
 */
@Entity
@Table(name = "user_growth_stats")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserGrowthStat extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_date", nullable = false, unique = true)
    private LocalDate statDate;

    private Long newUsers = 0L;
    private Long totalUsers = 0L;
    private Long activeUsers = 0L;
    private Long lockedUsers = 0L;
}

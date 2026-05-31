package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Cache tóm tắt AI của review tour (Ưu điểm / Nhược điểm / Lời khuyên).
 * Mỗi tour 1 row. Regenerate qua cron hoặc khi isStale = true.
 */
@Entity
@Table(name = "tour_review_summaries", indexes = {
        @Index(name = "idx_summary_tour", columnList = "tour_id", unique = true)
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourReviewSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tour_id", nullable = false, unique = true)
    private Integer tourId;

    @Column(name = "pros", columnDefinition = "TEXT")
    private String pros;

    @Column(name = "cons", columnDefinition = "TEXT")
    private String cons;

    @Column(name = "tips", columnDefinition = "TEXT")
    private String tips;

    @Column(name = "review_count_at_gen")
    private Integer reviewCountAtGen;

    @Column(name = "avg_rating_at_gen")
    private Double avgRatingAtGen;

    @Column(name = "model")
    private String model;

    @Column(name = "is_stale", nullable = false)
    @Builder.Default
    private Boolean isStale = false;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}

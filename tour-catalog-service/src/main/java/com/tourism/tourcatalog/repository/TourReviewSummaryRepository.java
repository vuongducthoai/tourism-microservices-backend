package com.tourism.tourcatalog.repository;

import com.tourism.tourcatalog.entity.TourReviewSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TourReviewSummaryRepository extends JpaRepository<TourReviewSummary, Long> {

    Optional<TourReviewSummary> findByTourId(Integer tourId);

    /** Targets cho cron regen: stale = true HOẶC generatedAt < threshold (cũ quá). */
    @Query("SELECT s FROM TourReviewSummary s " +
           "WHERE s.isStale = true OR s.generatedAt IS NULL OR s.generatedAt < :threshold")
    List<TourReviewSummary> findRegenTargets(@Param("threshold") LocalDateTime threshold);
}

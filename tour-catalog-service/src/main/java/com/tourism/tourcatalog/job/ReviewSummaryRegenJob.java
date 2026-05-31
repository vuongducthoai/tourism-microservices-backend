package com.tourism.tourcatalog.job;

import com.tourism.tourcatalog.service.ReviewSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron 02:00 mỗi đêm: regen mọi AI summary đang stale hoặc generatedAt cũ.
 * Disable bằng review-summary.cron-enabled=false trong môi trường test.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewSummaryRegenJob {

    private final ReviewSummaryService reviewSummaryService;

    @Value("${review-summary.cron-enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${review-summary.cron-expr:0 0 2 * * *}")
    public void regenStale() {
        if (!enabled) {
            log.debug("Review summary cron disabled — skipping");
            return;
        }
        long start = System.currentTimeMillis();
        int success = reviewSummaryService.regenStale();
        log.info("Review summary regen done: {} summaries in {}ms",
                success, System.currentTimeMillis() - start);
    }
}

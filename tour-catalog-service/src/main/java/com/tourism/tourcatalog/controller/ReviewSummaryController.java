package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.TourReviewSummaryResponse;
import com.tourism.tourcatalog.service.ReviewSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API tóm tắt review tour bằng AI.
 * - GET public: FE đọc cache (HIT/STALE/MISS).
 * - POST admin: force regen ngay (debug + sau khi chỉnh prompt).
 */
@RestController
@RequiredArgsConstructor
public class ReviewSummaryController {

    private final ReviewSummaryService reviewSummaryService;

    /** Public: FE gọi khi load tour detail. */
    @GetMapping("/api/tours/{tourCode}/review-summary")
    public ResponseEntity<?> getSummary(@PathVariable String tourCode) {
        TourReviewSummaryResponse res = reviewSummaryService.getSummaryByCode(tourCode);
        return ResponseEntity.ok(Map.of("success", true, "data", res));
    }

    /** Admin: force regen ngay (dùng tourId thực để chắc chắn). */
    @PostMapping("/api/admin/tours/{tourId}/review-summary/regenerate")
    public ResponseEntity<?> regenerate(@PathVariable Integer tourId) {
        try {
            TourReviewSummaryResponse res = reviewSummaryService.generateSummary(tourId);
            return ResponseEntity.ok(Map.of("success", true,
                    "message", "Đã tạo tóm tắt AI", "data", res));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", e.getMessage()));
        }
    }

    /** Admin: trigger regen cho toàn bộ summary stale/cũ. */
    @PostMapping("/api/admin/tours/review-summary/regenerate-stale")
    public ResponseEntity<?> regenerateStale() {
        int count = reviewSummaryService.regenStale();
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Đã regen " + count + " summary"));
    }
}

package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.response.TourReviewSummaryResponse;

public interface ReviewSummaryService {

    /** Đọc cache hiện có (HIT / STALE / MISS). Không gọi AI. */
    TourReviewSummaryResponse getSummary(Integer tourId);

    /** Đọc theo tourCode (tiện cho FE đang dùng tourCode). */
    TourReviewSummaryResponse getSummaryByCode(String tourCode);

    /** Gọi Groq và lưu DB. Throw nếu < min reviews. */
    TourReviewSummaryResponse generateSummary(Integer tourId);

    /** Đánh dấu stale khi có review mới. Fire-and-forget, không throw. */
    void markStale(Integer tourId);

    /** Regen mọi summary stale hoặc cũ quá threshold. Trả số đã regen. */
    int regenStale();
}

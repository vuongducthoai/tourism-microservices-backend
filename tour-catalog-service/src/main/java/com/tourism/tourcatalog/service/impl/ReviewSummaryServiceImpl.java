package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.internal.GroqSummaryResult;
import com.tourism.tourcatalog.dto.response.TourReviewSummaryResponse;
import com.tourism.tourcatalog.entity.Review;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourReviewSummary;
import com.tourism.tourcatalog.repository.ReviewRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.repository.TourReviewSummaryRepository;
import com.tourism.tourcatalog.service.GroqReviewSummaryClient;
import com.tourism.tourcatalog.service.ReviewSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewSummaryServiceImpl implements ReviewSummaryService {

    private final TourReviewSummaryRepository summaryRepository;
    private final ReviewRepository reviewRepository;
    private final TourRepository tourRepository;
    private final GroqReviewSummaryClient groqClient;

    @Value("${review-summary.min-reviews:10}")
    private int minReviews;

    @Value("${review-summary.max-reviews-feed:50}")
    private int maxReviewsFeed;

    @Value("${review-summary.regen-after-days:7}")
    private int regenAfterDays;

    @Override
    @Transactional
    public TourReviewSummaryResponse getSummary(Integer tourId) {
        return summaryRepository.findByTourId(tourId)
                .map(s -> {
                    s.setLastUsedAt(LocalDateTime.now());
                    summaryRepository.save(s);
                    return mapToResponse(s, Boolean.TRUE.equals(s.getIsStale()) ? "STALE" : "HIT");
                })
                .orElseGet(() -> emptyResponse("MISS"));
    }

    @Override
    @Transactional
    public TourReviewSummaryResponse getSummaryByCode(String tourCode) {
        Tour tour = tourRepository.findDetailByTourCode(tourCode).orElse(null);
        if (tour == null) return emptyResponse("MISS");
        return getSummary(tour.getTourID());
    }

    @Override
    @Transactional
    public TourReviewSummaryResponse generateSummary(Integer tourId) {
        Tour tour = tourRepository.findById(tourId)
                .orElseThrow(() -> new RuntimeException("Tour không tồn tại: " + tourId));

        List<Review> reviews = reviewRepository.findTopReviewsForSummary(
                tourId, PageRequest.of(0, maxReviewsFeed));
        if (reviews.size() < minReviews) {
            throw new RuntimeException("Cần ít nhất " + minReviews
                    + " review hợp lệ để tóm tắt (hiện có " + reviews.size() + ")");
        }

        log.info("Generating AI summary for tour {} ({} reviews)", tourId, reviews.size());
        GroqSummaryResult result = groqClient.summarize(tour.getTourName(), reviews);

        TourReviewSummary entity = summaryRepository.findByTourId(tourId)
                .orElseGet(() -> TourReviewSummary.builder().tourId(tourId).build());
        entity.setPros(result.pros());
        entity.setCons(result.cons());
        entity.setTips(result.tips());
        entity.setReviewCountAtGen(reviews.size());
        entity.setAvgRatingAtGen(reviews.stream()
                .filter(r -> r.getRating() != null)
                .mapToInt(Review::getRating).average().orElse(0));
        entity.setModel(result.model());
        entity.setGeneratedAt(LocalDateTime.now());
        entity.setLastUsedAt(LocalDateTime.now());
        entity.setIsStale(false);

        return mapToResponse(summaryRepository.save(entity), "GENERATED");
    }

    @Override
    public void markStale(Integer tourId) {
        if (tourId == null) return;
        try {
            summaryRepository.findByTourId(tourId).ifPresent(s -> {
                s.setIsStale(true);
                summaryRepository.save(s);
            });
        } catch (Exception e) {
            log.warn("markStale tour {} failed: {}", tourId, e.getMessage());
        }
    }

    @Override
    public int regenStale() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(regenAfterDays);
        List<TourReviewSummary> targets = summaryRepository.findRegenTargets(threshold);
        int success = 0;
        for (TourReviewSummary s : targets) {
            try {
                generateSummary(s.getTourId());
                success++;
                Thread.sleep(2000); // Rate-limit Groq free tier
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Regen tour {} failed: {}", s.getTourId(), e.getMessage());
            }
        }
        return success;
    }

    private TourReviewSummaryResponse mapToResponse(TourReviewSummary s, String cacheStatus) {
        if (s == null) return emptyResponse(cacheStatus);
        return TourReviewSummaryResponse.builder()
                .pros(s.getPros())
                .cons(s.getCons())
                .tips(s.getTips())
                .reviewCountAtGen(s.getReviewCountAtGen())
                .avgRatingAtGen(s.getAvgRatingAtGen())
                .model(s.getModel())
                .cacheStatus(cacheStatus)
                .isStale(s.getIsStale())
                .generatedAt(s.getGeneratedAt())
                .build();
    }

    private TourReviewSummaryResponse emptyResponse(String cacheStatus) {
        return TourReviewSummaryResponse.builder()
                .cacheStatus(cacheStatus)
                .isStale(false)
                .build();
    }
}

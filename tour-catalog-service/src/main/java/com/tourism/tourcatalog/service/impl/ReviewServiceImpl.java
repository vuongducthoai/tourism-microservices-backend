package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import com.tourism.tourcatalog.dto.response.ReviewStatisticsResponse;
import com.tourism.tourcatalog.dto.response.TourReviewListResponse;
import com.tourism.tourcatalog.entity.ImageReview;
import com.tourism.tourcatalog.entity.Review;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.feign.BookingFeignClient;
import com.tourism.tourcatalog.feign.IamFeignClient;
import com.tourism.tourcatalog.feign.dto.BookingBriefResponse;
import com.tourism.tourcatalog.feign.dto.UserBriefResponse;
import com.tourism.tourcatalog.repository.ReviewRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.FileStorageService;
import com.tourism.tourcatalog.service.ReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private static final int    MIN_COMMENT_LENGTH = 10;

    private final ReviewRepository  reviewRepository;
    private final TourRepository    tourRepository;
    private final FileStorageService fileStorageService;
    private final BookingFeignClient bookingClient;
    private final IamFeignClient     iamClient;

    // ── Submit review ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ReviewResponse submitReview(ReviewRequest request, List<MultipartFile> images) throws IOException {

        // 1. Validate rating
        if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5");
        }

        // 2. Check tour exists
        Tour tour = tourRepository.findById(request.getTourID())
                .orElseThrow(() -> new RuntimeException("Tour not found: " + request.getTourID()));

        // 3. Prevent duplicate review for same booking
        if (reviewRepository.existsByBookingId(request.getBookingID())) {
            throw new IllegalStateException("Review already submitted for booking: " + request.getBookingID());
        }

        // 4. Fetch booking info from booking-service to get userId and bookingCode
        Integer userId = request.getUserId(); // fallback if userId was sent by client
        String bookingCode = String.valueOf(request.getBookingID());
        try {
            BookingBriefResponse booking = bookingClient.getBookingById(request.getBookingID());
            if (booking.getUserId() != null) {
                userId = booking.getUserId();
            }
            if (booking.getBookingCode() != null) {
                bookingCode = booking.getBookingCode();
            }
        } catch (Exception e) {
            log.warn("Could not fetch booking info for bookingId={}: {}", request.getBookingID(), e.getMessage());
        }

        // 5. Build Review entity
        Review review = new Review();
        review.setRating(request.getRating());
        review.setComment(request.getComment());
        review.setBookingId(request.getBookingID());
        review.setUserId(userId);
        review.setTour(tour);
        review.setIsVisible(true);

        // 6. Save images locally
        int validImageCount = 0;
        List<ImageReview> imageReviews = new ArrayList<>();
        if (images != null) {
            for (MultipartFile file : images) {
                if (file != null && !file.isEmpty()) {
                    String url = fileStorageService.saveFile(file);
                    ImageReview img = new ImageReview();
                    img.setImageUrl(url);
                    img.setReview(review);
                    imageReviews.add(img);
                    validImageCount++;
                }
            }
        }
        review.setImages(imageReviews);

        // 7. Calculate coin reward points
        int coinPoints = calculateCoinPoints(
                request.getComment() != null ? request.getComment().length() : 0,
                validImageCount);

        // 8. Persist review (cascades to image_reviews)
        Review saved = reviewRepository.save(review);

        // 9. Fire-and-forget: update booking status → REVIEWED
        final Integer finalUserId = userId;
        try {
            bookingClient.updateBookingStatus(request.getBookingID(), "REVIEWED");
        } catch (Exception e) {
            log.error("Failed to update booking {} status to REVIEWED: {}", request.getBookingID(), e.getMessage());
        }

        // 10. Fire-and-forget: add coins to user (send raw points, not multiplied by rate)
        if (coinPoints > 0 && finalUserId != null) {
            try {
                BigDecimal coinAmount = BigDecimal.valueOf(coinPoints);
                iamClient.addCoins(finalUserId, coinAmount);
                log.info("Added {} coins to userId={} for review on booking {}",
                        coinAmount, finalUserId, request.getBookingID());
            } catch (Exception e) {
                log.error("Failed to add coins to userId={}: {}", finalUserId, e.getMessage());
            }
        }

        ReviewResponse res = toResponse(saved);
        res.setBookingCode(bookingCode);
        return res;
    }

    // ── Get review by booking ID ──────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ReviewResponse getReviewByBookingId(Integer bookingId) {
        Review review = reviewRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new RuntimeException("Review not found for booking: " + bookingId));
        return toResponse(review);
    }

    // ── Get reviews for a tour (paginated) ───────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<TourReviewListResponse> getReviewsByTour(String tourCode, Pageable pageable) {
        Page<Review> page = reviewRepository.findByTourCodeAndVisible(tourCode, pageable);
        return page.map(this::toListResponse);
    }

    // ── Get review statistics for a tour ─────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public ReviewStatisticsResponse getReviewStatistics(String tourCode) {
        ReviewStatisticsResponse stats = new ReviewStatisticsResponse();

        Integer total = reviewRepository.countByTourCode(tourCode);
        if (total == null) total = 0;
        stats.setTotalReviews(total);

        Double avg = reviewRepository.getAverageRatingByTourCode(tourCode);
        stats.setAverageRating(avg != null ? Math.round(avg * 10.0) / 10.0 : 0.0);

        int five  = orZero(reviewRepository.countByTourCodeAndRating(tourCode, 5));
        int four  = orZero(reviewRepository.countByTourCodeAndRating(tourCode, 4));
        int three = orZero(reviewRepository.countByTourCodeAndRating(tourCode, 3));
        int two   = orZero(reviewRepository.countByTourCodeAndRating(tourCode, 2));
        int one   = orZero(reviewRepository.countByTourCodeAndRating(tourCode, 1));

        stats.setFiveStars(five);
        stats.setFourStars(four);
        stats.setThreeStars(three);
        stats.setTwoStars(two);
        stats.setOneStar(one);

        if (total > 0) {
            stats.setFiveStarsPercent(round(five  * 100.0 / total));
            stats.setFourStarsPercent(round(four  * 100.0 / total));
            stats.setThreeStarsPercent(round(three * 100.0 / total));
            stats.setTwoStarsPercent(round(two   * 100.0 / total));
            stats.setOneStarPercent(round(one   * 100.0 / total));
        } else {
            stats.setFiveStarsPercent(0.0);
            stats.setFourStarsPercent(0.0);
            stats.setThreeStarsPercent(0.0);
            stats.setTwoStarsPercent(0.0);
            stats.setOneStarPercent(0.0);
        }

        return stats;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Calculates coin reward points based on comment length and image count.
     * comment < 10 chars  → 0 pts
     * comment >= 10, no images → 5 pts
     * comment >= 10, 1 image  → 7 pts
     * comment >= 10, 2+ images → 10 pts
     */
    private int calculateCoinPoints(int commentLength, int imageCount) {
        if (commentLength < MIN_COMMENT_LENGTH) return 0;
        if (imageCount == 0) return 5;
        if (imageCount == 1) return 7;
        return 10;
    }

    private ReviewResponse toResponse(Review review) {
        ReviewResponse res = new ReviewResponse();
        res.setReviewID(review.getReviewID());
        res.setRating(review.getRating());
        res.setComment(review.getComment());
        if (review.getTour() != null) {
            res.setTourCode(review.getTour().getTourCode());
        }
        res.setBookingCode(String.valueOf(review.getBookingId()));
        List<String> urls = review.getImages() != null
                ? review.getImages().stream().map(ImageReview::getImageUrl).collect(Collectors.toList())
                : new ArrayList<>();
        res.setImageUrls(urls);
        return res;
    }

    private TourReviewListResponse toListResponse(Review review) {
        TourReviewListResponse res = new TourReviewListResponse();
        res.setReviewId(review.getReviewID());
        res.setRating(review.getRating());
        res.setComment(review.getComment());
        res.setCreatedAt(review.getCreatedAt());

        // Enrich with user info from iam-service (best-effort)
        TourReviewListResponse.UserInfo userInfo = new TourReviewListResponse.UserInfo();
        userInfo.setUserId(review.getUserId());
        if (review.getUserId() != null) {
            try {
                UserBriefResponse user = iamClient.getUserById(review.getUserId());
                userInfo.setFullName(user.getFullName());
                userInfo.setAvatar(user.getAvatar());
                userInfo.setEmail(user.getEmail());
            } catch (Exception e) {
                log.warn("Could not fetch user info for userId={}: {}", review.getUserId(), e.getMessage());
                userInfo.setFullName("Khách hàng");
            }
        }
        res.setUser(userInfo);

        List<String> urls = review.getImages() != null
                ? review.getImages().stream().map(ImageReview::getImageUrl).collect(Collectors.toList())
                : new ArrayList<>();
        res.setImages(urls);
        return res;
    }

    private int orZero(Integer value) {
        return value != null ? value : 0;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}

package com.tourism.tourcatalog.service.impl;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import com.tourism.tourcatalog.entity.ImageReview;
import com.tourism.tourcatalog.entity.Review;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.repository.ReviewRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final TourRepository   tourRepository;
    private final Cloudinary       cloudinary;

    @Override
    @Transactional
    public ReviewResponse submitReview(ReviewRequest request, List<MultipartFile> images) throws IOException {

        // 1. Validate rating range
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

        // 4. Build Review entity
        Review review = new Review();
        review.setRating(request.getRating());
        review.setComment(request.getComment());
        review.setBookingId(request.getBookingID());
        review.setUserId(request.getUserId());
        review.setTour(tour);
        review.setIsVisible(true);

        // 5. Upload images to Cloudinary and attach
        List<ImageReview> imageReviews = new ArrayList<>();
        if (images != null) {
            for (MultipartFile file : images) {
                if (file != null && !file.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> uploadResult = cloudinary.uploader().upload(
                            file.getBytes(),
                            ObjectUtils.asMap("folder", "tourism_reviews")
                    );
                    String url = (String) uploadResult.get("secure_url");
                    ImageReview img = new ImageReview();
                    img.setImageUrl(url);
                    img.setReview(review);
                    imageReviews.add(img);
                }
            }
        }
        review.setImages(imageReviews);

        // 6. Save
        Review saved = reviewRepository.save(review);

        // NOTE: Updating booking status to REVIEWED is handled by booking-service
        // (TODO: send RabbitMQ event or call BookingFeignClient when booking-service ready)

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewResponse getReviewByBookingId(Integer bookingId) {
        Review review = reviewRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new RuntimeException("Review not found for booking: " + bookingId));
        return toResponse(review);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private ReviewResponse toResponse(Review review) {
        ReviewResponse res = new ReviewResponse();
        res.setReviewID(review.getReviewID());
        res.setRating(review.getRating());
        res.setComment(review.getComment());
        if (review.getTour() != null) {
            res.setTourCode(review.getTour().getTourCode());
        }
        // bookingCode is cross-service; return bookingId as string for now
        res.setBookingCode(String.valueOf(review.getBookingId()));
        List<String> urls = new ArrayList<>();
        if (review.getImages() != null) {
            for (ImageReview img : review.getImages()) {
                urls.add(img.getImageUrl());
            }
        }
        res.setImageUrls(urls);
        return res;
    }
}

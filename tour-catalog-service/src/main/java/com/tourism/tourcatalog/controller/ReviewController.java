package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.ReviewChatbotSyncResponse;
import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import com.tourism.tourcatalog.dto.response.ReviewStatisticsResponse;
import com.tourism.tourcatalog.dto.response.TourReviewListResponse;
import com.tourism.tourcatalog.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /**
     * POST /api/reviews
     * Submit a review. Accepts multipart/form-data with optional images.
     * Fields: rating, comment, tourID, bookingID, [userId optional]
     * Note: userId is optional — backend fetches it from booking-service via Feign.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReviewResponse> submitReview(
            @RequestPart("rating")    String ratingStr,
            @RequestPart("comment")   String comment,
            @RequestPart("tourID")    String tourIdStr,
            @RequestPart("bookingID") String bookingIdStr,
            @RequestPart(value = "userId",  required = false) String userIdStr,
            @RequestPart(value = "images",  required = false) List<MultipartFile> images
    ) throws IOException {
        ReviewRequest req = new ReviewRequest();
        req.setRating(Integer.parseInt(ratingStr));
        req.setComment(comment);
        req.setTourID(Integer.parseInt(tourIdStr));
        req.setBookingID(Integer.parseInt(bookingIdStr));
        if (userIdStr != null && !userIdStr.isBlank()) {
            req.setUserId(Integer.parseInt(userIdStr));
        }

        ReviewResponse response = reviewService.submitReview(req, images);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/reviews/{bookingID}
     * Get review by booking ID (returns 404 if not yet reviewed).
     */
    @GetMapping("/{bookingID}")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable Integer bookingID) {
        return ResponseEntity.ok(reviewService.getReviewByBookingId(bookingID));
    }

    /**
     * GET /api/reviews/tour/{tourCode}?page=0&size=5
     * Paginated list of visible reviews for a tour, newest first.
     */
    @GetMapping("/tour/{tourCode}")
    public ResponseEntity<Page<TourReviewListResponse>> getReviewsByTour(
            @PathVariable String tourCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(reviewService.getReviewsByTour(tourCode, pageable));
    }

    /**
     * GET /api/reviews/tour/{tourCode}/statistics
     * Rating statistics for a tour: average, count by star, percentages.
     */
    @GetMapping("/tour/{tourCode}/statistics")
    public ResponseEntity<ReviewStatisticsResponse> getReviewStatistics(@PathVariable String tourCode) {
        return ResponseEntity.ok(reviewService.getReviewStatistics(tourCode));
    }

    /**
     * GET /api/reviews/chatbot-sync
     * Lấy tất cả review visible để analytics-service sync lên Pinecone.
     * Endpoint nội bộ — gọi từ analytics-service qua Feign.
     */
    @GetMapping("/chatbot-sync")
    public ResponseEntity<List<ReviewChatbotSyncResponse>> getChatbotSyncReviews() {
        return ResponseEntity.ok(reviewService.getAllVisibleReviewsForChatbot());
    }
}


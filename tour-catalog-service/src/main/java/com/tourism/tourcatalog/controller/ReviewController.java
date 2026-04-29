package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import com.tourism.tourcatalog.service.ReviewService;
import lombok.RequiredArgsConstructor;
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
     * Submit a review for a tour. Accepts multipart/form-data with optional images.
     * Fields: rating, comment, tourID, bookingID, userId, images[] (optional)
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReviewResponse> submitReview(
            @RequestPart("rating")    String ratingStr,
            @RequestPart("comment")   String comment,
            @RequestPart("tourID")    String tourIdStr,
            @RequestPart("bookingID") String bookingIdStr,
            @RequestPart("userId")    String userIdStr,
            @RequestPart(value = "images", required = false) List<MultipartFile> images
    ) throws IOException {
        ReviewRequest req = new ReviewRequest();
        req.setRating(Integer.parseInt(ratingStr));
        req.setComment(comment);
        req.setTourID(Integer.parseInt(tourIdStr));
        req.setBookingID(Integer.parseInt(bookingIdStr));
        req.setUserId(Integer.parseInt(userIdStr));

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
}

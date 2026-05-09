package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.ReviewChatbotSyncResponse;
import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import com.tourism.tourcatalog.dto.response.ReviewStatisticsResponse;
import com.tourism.tourcatalog.dto.response.TourReviewListResponse;
import com.tourism.tourcatalog.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Reviews", description = "Đánh giá tour — gửi, xem, thống kê")
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "Gửi đánh giá tour", description = "Multipart/form-data: rating, comment, tourID, bookingID, images (optional)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Đánh giá đã được gửi"),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ")
    })
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

    @Operation(summary = "Xem đánh giá theo bookingID", description = "Trả về đánh giá của booking — 404 nếu chưa đánh giá")
    @ApiResponse(responseCode = "200", description = "Đánh giá")
    @GetMapping("/{bookingID}")
    public ResponseEntity<ReviewResponse> getReview(@PathVariable Integer bookingID) {
        return ResponseEntity.ok(reviewService.getReviewByBookingId(bookingID));
    }

    @Operation(summary = "Danh sách đánh giá theo tour", description = "Phân trang, mới nhất trước. Mặc định page=0, size=5")
    @ApiResponse(responseCode = "200", description = "Đánh giá phân trang")
    @GetMapping("/tour/{tourCode}")
    public ResponseEntity<Page<TourReviewListResponse>> getReviewsByTour(
            @PathVariable String tourCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size
    ) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(reviewService.getReviewsByTour(tourCode, pageable));
    }

    @Operation(summary = "Thống kê đánh giá tour", description = "Trả về: điểm trung bình, số lượng theo sao, phần trăm")
    @ApiResponse(responseCode = "200", description = "Thống kê đánh giá")
    @GetMapping("/tour/{tourCode}/statistics")
    public ResponseEntity<ReviewStatisticsResponse> getReviewStatistics(@PathVariable String tourCode) {
        return ResponseEntity.ok(reviewService.getReviewStatistics(tourCode));
    }

    @Operation(summary = "[Internal] Chatbot sync reviews", description = "Endpoint nội bộ — analytics-service gọi qua Feign để lấy review cho Pinecone")
    @ApiResponse(responseCode = "200", description = "Danh sách review")
    @GetMapping("/chatbot-sync")
    public ResponseEntity<List<ReviewChatbotSyncResponse>> getChatbotSyncReviews() {
        return ResponseEntity.ok(reviewService.getAllVisibleReviewsForChatbot());
    }
}


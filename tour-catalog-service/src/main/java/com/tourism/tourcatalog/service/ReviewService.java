package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import com.tourism.tourcatalog.dto.response.ReviewStatisticsResponse;
import com.tourism.tourcatalog.dto.response.TourReviewListResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ReviewService {

    ReviewResponse submitReview(ReviewRequest request, List<MultipartFile> images) throws IOException;

    ReviewResponse getReviewByBookingId(Integer bookingId);

    Page<TourReviewListResponse> getReviewsByTour(String tourCode, Pageable pageable);

    ReviewStatisticsResponse getReviewStatistics(String tourCode);
}

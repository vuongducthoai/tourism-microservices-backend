package com.tourism.tourcatalog.service;

import com.tourism.tourcatalog.dto.request.ReviewRequest;
import com.tourism.tourcatalog.dto.response.ReviewResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ReviewService {

    ReviewResponse submitReview(ReviewRequest request, List<MultipartFile> images) throws IOException;

    ReviewResponse getReviewByBookingId(Integer bookingId);
}

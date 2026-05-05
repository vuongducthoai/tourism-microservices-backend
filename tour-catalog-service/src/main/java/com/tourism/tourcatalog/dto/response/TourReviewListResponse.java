package com.tourism.tourcatalog.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO for tour review listing endpoint.
 * GET /api/reviews/tour/{tourCode}?page=&size=
 *
 * Frontend (TourReviews.jsx) expects:
 *   reviewId, rating, comment, createdAt,
 *   user.fullName, user.avatar,
 *   images (list of URL strings)
 */
@Data
@NoArgsConstructor
public class TourReviewListResponse {

    private Integer       reviewId;
    private Integer       rating;
    private String        comment;
    private LocalDateTime createdAt;
    private UserInfo      user;
    private List<String>  images;

    @Data
    @NoArgsConstructor
    public static class UserInfo {
        private Integer userId;
        private String  fullName;
        private String  avatar;
        private String  email;
    }
}

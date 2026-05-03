package com.tourism.tourcatalog.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Statistics summary for reviews of a specific tour.
 * GET /api/reviews/tour/{tourCode}/statistics
 *
 * Frontend (TourReviews.jsx) expects:
 *   averageRating, totalReviews,
 *   fiveStars, fourStars, threeStars, twoStars, oneStar,
 *   fiveStarsPercent, fourStarsPercent, threeStarsPercent, twoStarsPercent, oneStarPercent
 */
@Data
@NoArgsConstructor
public class ReviewStatisticsResponse {

    private Double  averageRating;
    private Integer totalReviews;

    private Integer fiveStars;
    private Integer fourStars;
    private Integer threeStars;
    private Integer twoStars;
    private Integer oneStar;

    private Double fiveStarsPercent;
    private Double fourStarsPercent;
    private Double threeStarsPercent;
    private Double twoStarsPercent;
    private Double oneStarPercent;
}

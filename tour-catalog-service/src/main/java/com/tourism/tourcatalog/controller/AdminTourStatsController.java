package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.stats.TourPerformanceItem;
import com.tourism.tourcatalog.dto.response.stats.TourStatsResponse;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.repository.ReviewRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/tours")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Admin Stats", description = "Thống kê tour nội bộ dùng cho Dashboard")
public class AdminTourStatsController {

    private final TourRepository tourRepository;
    private final ReviewRepository reviewRepository;

    @GetMapping("/stats")
    @Operation(summary = "Lấy thống kê tours cho Dashboard")
    @ApiResponse(responseCode = "200", description = "Tour stats")
    public ResponseEntity<TourStatsResponse> getTourStats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        log.info("Fetching tour stats for dashboard from={} to={}", from, to);

        Long totalTours = tourRepository.count();
        Long activeTours = safe(tourRepository.countByStatus(true));
        Long totalDepartures = safe(tourRepository.countAllDepartures());
        Long upcomingDepartures = safe(tourRepository.countUpcomingDepartures(LocalDateTime.now()));
        Double averageRating = reviewRepository.calculateAverageRating();

        // Tour performance: top 10 theo average rating
        List<TourPerformanceItem> tourPerformance = tourRepository.findAllActiveWithDetails()
                .stream()
                .map(t -> {
                    Double rating = reviewRepository.getAverageRatingByTourCode(t.getTourCode());
                    return TourPerformanceItem.builder()
                            .tourName(t.getTourName())
                            .bookings(0L)
                            .revenue(0L)
                            .rating(rating != null ? rating : 0.0)
                            .build();
                })
                .sorted(Comparator.comparingDouble(TourPerformanceItem::getRating).reversed())
                .limit(10)
                .collect(Collectors.toList());

        return ResponseEntity.ok(TourStatsResponse.builder()
                .totalTours(totalTours)
                .activeTours(activeTours)
                .totalDepartures(totalDepartures)
                .upcomingDepartures(upcomingDepartures)
                .averageRating(averageRating != null ? averageRating : 0.0)
                .tourPerformance(tourPerformance)
                .build());
    }

    private Long safe(Long val) { return val != null ? val : 0L; }
}

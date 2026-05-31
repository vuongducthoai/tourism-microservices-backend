package com.tourism.tourcatalog.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourReviewSummaryResponse {
    private String pros;
    private String cons;
    private String tips;
    private Integer reviewCountAtGen;
    private Double avgRatingAtGen;
    private String model;

    /** HIT | STALE | MISS | GENERATED */
    private String cacheStatus;
    private Boolean isStale;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime generatedAt;
}

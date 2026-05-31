package com.tourism.tourcatalog.dto.response;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourRouteResponse {
    private String tourCode;
    private List<TourStopResponse> stops;

    /** Bounding box để FE auto-fit map. */
    private Double minLat, maxLat, minLng, maxLng;

    /** Số ngày có stop (dayNumber not null, distinct, sorted) — render chip filter. */
    private List<Integer> availableDays;
}

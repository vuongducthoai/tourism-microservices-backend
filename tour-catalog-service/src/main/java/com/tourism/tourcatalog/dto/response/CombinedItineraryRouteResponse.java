package com.tourism.tourcatalog.dto.response;

import lombok.*;

import java.util.List;

/**
 * Response composite cho /tours/{code}/itinerary-with-route — 1 fetch trả đủ
 * data render cả <TourItinerary> và <TourRouteMap> với globalIndex khớp nhau.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CombinedItineraryRouteResponse {

    private String tourCode;
    private List<DayWithStopsResponse> days;

    /** Stops không gắn day (dayNumber null) hoặc trỏ vào day không tồn tại. */
    private List<StopWithIndexResponse> orphanStops;

    /** dayNumber có trong itinerary nhưng chưa có stop nào. */
    private List<Integer> missingStopDays;

    /** Bounding box cho map fitBounds. Null nếu không có stop. */
    private Double minLat, maxLat, minLng, maxLng;
}

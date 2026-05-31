package com.tourism.tourcatalog.dto.response;

import lombok.*;

/**
 * Stop trong response composite — kèm globalIndex để FE itinerary và map khớp số badge.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StopWithIndexResponse {
    private Integer stopId;
    private String name;
    private Double latitude;
    private Double longitude;
    private Integer stopOrder;

    /** Số thứ tự toàn cục 1..N — khớp pin trên map. */
    private Integer globalIndex;

    private String description;
    private String stopType;
}

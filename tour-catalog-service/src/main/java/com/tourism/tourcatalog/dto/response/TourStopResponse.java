package com.tourism.tourcatalog.dto.response;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourStopResponse {
    private Integer stopId;
    private String name;
    private Double latitude;
    private Double longitude;
    private Integer stopOrder;
    private String description;
    private String stopType;

    private Integer dayNumber;
    private String dayTitle;
}

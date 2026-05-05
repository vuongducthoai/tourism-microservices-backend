package com.tourism.analytics.dto.feign;

import lombok.*;

/**
 * Response DTO nhận từ GET /api/locations/end-location của tour-catalog-service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LocationSyncDTO {
    private Integer locationID;
    private String  name;
    private String  imageUrl;
    private String  description;
    private String  region;
    private String  airportCode;
    private String  airportName;
}

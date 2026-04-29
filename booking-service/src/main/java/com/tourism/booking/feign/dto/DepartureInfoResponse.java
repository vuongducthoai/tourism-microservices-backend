package com.tourism.booking.feign.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DepartureInfoResponse {
    private Integer departureID;
    private String  departureDate;
    private Integer tourID;
    private String  tourCode;
    private String  tourName;
    private String  image;
    private String  duration;
}

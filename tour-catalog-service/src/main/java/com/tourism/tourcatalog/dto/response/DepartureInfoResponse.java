package com.tourism.tourcatalog.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class DepartureInfoResponse {
    private Integer departureID;
    private String  departureDate;   // ISO date string
    private Integer tourID;
    private String  tourCode;
    private String  tourName;
    private String  image;           // first tour image
    private String  duration;
}

package com.tourism.tourcatalog.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryDayResponse {
    private Integer dayNumber;
    private String title;
    private String details;
    private String meals;
}

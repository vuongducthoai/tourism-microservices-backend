package com.tourism.tourcatalog.dto.request.admin;

import lombok.Data;

@Data
public class ItineraryDayRequest {
    private Integer dayNumber;
    private String title;
    private String details;
    private String meals;
}

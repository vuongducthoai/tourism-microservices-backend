package com.tourism.tourcatalog.feign.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartureBookedCountResponse {
    private Integer departureId;
    private Integer bookedPassengers;
}

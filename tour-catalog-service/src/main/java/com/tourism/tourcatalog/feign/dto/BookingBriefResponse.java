package com.tourism.tourcatalog.feign.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight booking info received from booking-service via Feign.
 */
@Data
@NoArgsConstructor
public class BookingBriefResponse {
    private Integer bookingID;
    private String  bookingCode;
    private String  bookingStatus;
    private Integer userId;
}

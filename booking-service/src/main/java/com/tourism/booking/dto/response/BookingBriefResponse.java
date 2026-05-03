package com.tourism.booking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight booking info returned to internal services via Feign.
 * Used by tour-catalog-service to get userId and bookingCode when creating a review.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingBriefResponse {
    private Integer bookingID;
    private String  bookingCode;
    private String  bookingStatus;
    private Integer userId;
}

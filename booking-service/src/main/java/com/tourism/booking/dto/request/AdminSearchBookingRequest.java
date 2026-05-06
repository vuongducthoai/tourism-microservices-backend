package com.tourism.booking.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Request DTO for POST /api/bookings/admin/search
 * Mirrors monolith's BookingSearchRequestDTO.
 *
 * Fields:
 *  bookingCode   — partial match (LIKE), nullable
 *  bookingStatus — exact match (enum name), nullable
 *  bookingDate   — filter for the whole day (BETWEEN startOfDay and endOfDay), nullable
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminSearchBookingRequest {

    private String        bookingCode;
    private String        bookingStatus;
    private LocalDateTime bookingDate;
}

package com.tourism.booking.repository;

import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.entity.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Custom JPA repository for dynamic booking queries using Criteria API.
 * Mirrors monolith's BookingRepositoryCustom.
 */
public interface BookingRepositoryCustom {

    /**
     * Dynamic search with optional filters:
     *  - bookingCode   : partial LIKE (case-insensitive)
     *  - bookingStatus : exact enum match
     *  - bookingDate   : BETWEEN startOfDay and endOfDay
     *
     * @param request  filter criteria (nulls ignored)
     * @param pageable Spring Pageable (page, size, sort)
     * @return paginated list of matching Booking entities
     */
    Page<Booking> searchBookings(AdminSearchBookingRequest request, Pageable pageable);
}

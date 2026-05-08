package com.tourism.booking.service;

import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.dto.request.AdminUpdateStatusRequest;
import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.dto.response.BookingBriefResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BookingService {

    List<BookingResponse> getBookingsByUser(Integer userId, String bookingStatus);

    BookingBriefResponse getBookingById(Integer bookingId);

    /** Internal: update status called by tour-catalog-service after review submitted */
    void updateBookingStatus(Integer bookingId, String status);

    BookingResponse cancelBooking(CancelBookingRequest request);

    BookingResponse submitRefundRequest(Integer bookingId, RefundInformationRequest request);

    /**
     * Admin: paginated + filtered booking search.
     * Mirrors monolith POST /api/bookings/admin/search
     */
    Page<BookingResponse> adminSearchBookings(AdminSearchBookingRequest request, Pageable pageable);

    /**
     * Admin: update booking status with business rules.
     * Supported: PENDING_CONFIRMATION→PAID, any-valid→CANCELLED
     * Mirrors monolith POST /api/bookings/admin/update-status
     */
    BookingResponse adminUpdateBookingStatus(AdminUpdateStatusRequest request);
}

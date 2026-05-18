package com.tourism.booking.service;

import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.dto.request.AdminUpdateStatusRequest;
import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.CreateBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingOrderResponse;
import com.tourism.booking.dto.response.BookingPaymentDetailResponse;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.dto.response.BookingBriefResponse;
import com.tourism.booking.dto.response.CreateBookingResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface BookingService {

    BookingOrderResponse getOrderInfo(String tourCode, Integer departureId);

    BookingPaymentDetailResponse getBookingPaymentDetail(String bookingCode);

    CreateBookingResponse createBooking(CreateBookingRequest request);

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

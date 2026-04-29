package com.tourism.booking.service;

import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingResponse;

import java.util.List;

public interface BookingService {

    List<BookingResponse> getBookingsByUser(Integer userId, String bookingStatus);

    BookingResponse cancelBooking(CancelBookingRequest request);

    BookingResponse submitRefundRequest(Integer bookingId, RefundInformationRequest request);
}

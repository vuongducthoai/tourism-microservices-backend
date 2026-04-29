package com.tourism.booking.controller;

import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * GET /api/bookings/user/{userID}?bookingStatus=PAID
     * Returns bookings for a user, optionally filtered by status.
     * bookingStatus values: PENDING_PAYMENT, OVERDUE_PAYMENT, PENDING_CONFIRMATION,
     *                       PAID, CANCELLED, PENDING_REVIEW, REVIEWED, PENDING_REFUND
     */
    @GetMapping("/user/{userID}")
    public ResponseEntity<List<BookingResponse>> getBookingsByUser(
            @PathVariable Integer userID,
            @RequestParam(required = false) String bookingStatus) {
        return ResponseEntity.ok(bookingService.getBookingsByUser(userID, bookingStatus));
    }

    /**
     * POST /api/bookings/cancel
     * Cancel a booking. Body: { bookingID, cancelReason }
     * Logic:
     *  - PENDING_PAYMENT → CANCELLED (no fee, no money paid)
     *  - PAID/PENDING_CONFIRMATION → calculates refund based on days to departure → PENDING_REFUND or CANCELLED
     */
    @PostMapping("/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(@RequestBody CancelBookingRequest request) {
        return ResponseEntity.ok(bookingService.cancelBooking(request));
    }

    /**
     * POST /api/bookings/refund-request/{bookingID}
     * Submit bank account info for refund. Body: { accountName, accountNumber, bank }
     * Booking must be in PENDING_REFUND status.
     */
    @PostMapping("/refund-request/{bookingID}")
    public ResponseEntity<BookingResponse> submitRefundRequest(
            @PathVariable Integer bookingID,
            @RequestBody RefundInformationRequest request) {
        return ResponseEntity.ok(bookingService.submitRefundRequest(bookingID, request));
    }
}

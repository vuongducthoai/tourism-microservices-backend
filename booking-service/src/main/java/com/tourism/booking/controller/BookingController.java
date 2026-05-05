package com.tourism.booking.controller;

import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingBriefResponse;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.dto.response.CouponChatbotSyncResponse;
import com.tourism.booking.repository.CouponRepository;
import com.tourism.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService    bookingService;
    private final CouponRepository  couponRepository;

    /**
     * GET /api/coupons/chatbot-sync
     * Internal endpoint for analytics-service to fetch all active coupons for Pinecone sync.
     */
    @GetMapping("/coupons/chatbot-sync")
    public ResponseEntity<List<CouponChatbotSyncResponse>> getCouponsForChatbotSync() {
        List<CouponChatbotSyncResponse> result = couponRepository.findActiveCoupons(LocalDateTime.now())
                .stream()
                .map(c -> CouponChatbotSyncResponse.builder()
                        .couponID(c.getCouponID())
                        .couponCode(c.getCouponCode())
                        .description(c.getDescription())
                        .discountAmount(c.getDiscountAmount())
                        .startDate(c.getStartDate() != null ? c.getStartDate().toString() : null)
                        .endDate(c.getEndDate() != null ? c.getEndDate().toString() : null)
                        .usageLimit(c.getUsageLimit())
                        .usageCount(c.getUsageCount())
                        .couponType(c.getCouponType() != null ? c.getCouponType().name() : "GLOBAL")
                        .departureId(c.getDepartureId())
                        .build())
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/bookings/{bookingID}
     * Internal endpoint: returns lightweight booking info (userId, status) for Feign callers.
     * Used by tour-catalog-service after review submission.
     */
    @GetMapping("/{bookingID}")
    public ResponseEntity<BookingBriefResponse> getBookingById(@PathVariable Integer bookingID) {
        return ResponseEntity.ok(bookingService.getBookingById(bookingID));
    }

    /**
     * POST /api/bookings/{bookingID}/status?status=REVIEWED
     * Internal endpoint: update booking status. Called by tour-catalog-service after review submitted.
     * NOTE: Using POST (not PATCH) because Java HttpURLConnection used by Feign does not support PATCH.
     */
    @PostMapping("/{bookingID}/status")
    public ResponseEntity<Void> updateBookingStatus(
            @PathVariable Integer bookingID,
            @RequestParam String status) {
        bookingService.updateBookingStatus(bookingID, status);
        return ResponseEntity.ok().build();
    }

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

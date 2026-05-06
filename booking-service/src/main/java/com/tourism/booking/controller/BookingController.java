package com.tourism.booking.controller;

import com.tourism.booking.dto.request.AdminSearchBookingRequest;
import com.tourism.booking.dto.request.AdminUpdateStatusRequest;
import com.tourism.booking.dto.request.CancelBookingRequest;
import com.tourism.booking.dto.request.RefundInformationRequest;
import com.tourism.booking.dto.response.BookingBriefResponse;
import com.tourism.booking.dto.response.BookingResponse;
import com.tourism.booking.dto.response.CouponChatbotSyncResponse;
import com.tourism.booking.repository.CouponRepository;
import com.tourism.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
     */
    @GetMapping("/user/{userID}")
    public ResponseEntity<List<BookingResponse>> getBookingsByUser(
            @PathVariable Integer userID,
            @RequestParam(required = false) String bookingStatus) {
        return ResponseEntity.ok(bookingService.getBookingsByUser(userID, bookingStatus));
    }

    /**
     * POST /api/bookings/cancel
     * Cancel a booking (customer-side, coin refund path).
     */
    @PostMapping("/cancel")
    public ResponseEntity<BookingResponse> cancelBooking(@RequestBody CancelBookingRequest request) {
        return ResponseEntity.ok(bookingService.cancelBooking(request));
    }

    /**
     * POST /api/bookings/refund-request/{bookingID}
     * Submit bank account info for refund. Booking must be in PENDING_REFUND status.
     */
    @PostMapping("/refund-request/{bookingID}")
    public ResponseEntity<BookingResponse> submitRefundRequest(
            @PathVariable Integer bookingID,
            @RequestBody RefundInformationRequest request) {
        return ResponseEntity.ok(bookingService.submitRefundRequest(bookingID, request));
    }

    // ── ADMIN ENDPOINTS ───────────────────────────────────────────────────────

    /**
     * POST /api/bookings/admin/search
     * Admin: search bookings with pagination and optional filters.
     * Body: { bookingCode, bookingStatus, bookingDate }
     * Params: page, size, sortBy, sortDir
     *
     * Mirrors monolith POST /api/bookings/admin/search
     */
    @PostMapping("/admin/search")
    public ResponseEntity<Page<BookingResponse>> adminSearchBookings(
            @RequestBody AdminSearchBookingRequest request,
            @RequestParam(defaultValue = "0")             int    page,
            @RequestParam(defaultValue = "10")            int    size,
            @RequestParam(defaultValue = "bookingDate")   String sortBy,
            @RequestParam(defaultValue = "DESC")          String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);

        return ResponseEntity.ok(bookingService.adminSearchBookings(request, pageable));
    }

    /**
     * POST /api/bookings/admin/update-status
     * Admin: update booking status with business-rule validation.
     * Body: { bookingID, bookingStatus, cancelReason? }
     *
     * Supported transitions:
     *  PENDING_CONFIRMATION → PAID
     *  PENDING_PAYMENT      → CANCELLED (no refund)
     *  PENDING_CONFIRMATION → CANCELLED (with refund)
     *  PAID                 → CANCELLED (with refund)
     *  PENDING_REFUND       → CANCELLED (with refund, after admin bank transfer)
     *
     * Mirrors monolith POST /api/bookings/admin/update-status
     */
    @PostMapping("/admin/update-status")
    public ResponseEntity<?> adminUpdateBookingStatus(
            @RequestBody AdminUpdateStatusRequest request) {
        try {
            BookingResponse updated = bookingService.adminUpdateBookingStatus(request);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("message", e.getMessage()));
        }
    }
}

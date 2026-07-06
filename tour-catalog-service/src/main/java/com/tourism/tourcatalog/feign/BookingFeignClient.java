package com.tourism.tourcatalog.feign;

import com.tourism.tourcatalog.feign.dto.BookingBriefResponse;
import com.tourism.tourcatalog.feign.dto.CouponBriefResponse;
import com.tourism.tourcatalog.feign.dto.DepartureBookedCountResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.util.List;

/**
 * Feign client for calling booking-service endpoints.
 * Used by ReviewServiceImpl to fetch userId and update booking status to REVIEWED.
 */
@FeignClient(name = "booking-service")
public interface BookingFeignClient {

    /**
     * GET /api/bookings/{bookingId}
     * Returns lightweight booking info: bookingID, bookingCode, bookingStatus, userId.
     */
    @GetMapping("/api/bookings/{bookingId}")
    BookingBriefResponse getBookingById(@PathVariable Integer bookingId);

    /**
     * POST /api/bookings/{bookingId}/status?status=REVIEWED
     * Updates booking status after a review is submitted.
     * NOTE: Using POST (not PATCH) — Java HttpURLConnection does not support PATCH natively.
     */
    @PostMapping("/api/bookings/{bookingId}/status")
    void updateBookingStatus(@PathVariable Integer bookingId, @RequestParam String status);

    @GetMapping("/api/coupons/departure/{couponId}")
    CouponBriefResponse getCouponByDepartureId(@PathVariable Integer couponId);

    @GetMapping("/api/coupons/global")
    CouponBriefResponse getBestGlobalCoupon(@RequestParam BigDecimal orderValue);

    /** Coupon DEPARTURE tốt nhất cho nhiều lịch cùng lúc (mỗi phần tử có departureId tương ứng). */
    @GetMapping("/api/coupons/best-for-departures")
    List<CouponBriefResponse> getBestCouponsForDepartures(@RequestParam List<Integer> departureIds);

    /** Số khách đã đặt theo từng lịch khởi hành (loại trừ booking đã hủy). */
    @GetMapping("/api/bookings/booked-counts")
    List<DepartureBookedCountResponse> getBookedCounts(@RequestParam List<Integer> departureIds);
}

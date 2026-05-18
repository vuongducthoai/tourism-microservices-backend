package com.tourism.booking.feign;

import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.event.CouponEventDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Calls notification-service directly (no message queue).
 * Fire-and-forget: booking-service catches exceptions so the booking
 * transaction is never rolled back due to notification failures.
 */
@FeignClient(name = "notification-service")
public interface NotificationFeignClient {

    @PostMapping("/api/notifications/refund-requested")
    void notifyRefundRequested(@RequestBody BookingEventDTO event);

    @PostMapping("/api/notifications/status-updated")
    void notifyStatusUpdated(@RequestBody BookingEventDTO event);

    @PostMapping("/api/notifications/booking-confirmed")
    void notifyBookingConfirmed(@RequestBody BookingEventDTO event);

    @PostMapping("/api/notifications/coupon-created")
    void notifyCouponCreated(@RequestBody CouponEventDTO event);

    @PostMapping("/api/notifications/coupon-updated")
    void notifyCouponUpdated(@RequestBody CouponEventDTO event);
}

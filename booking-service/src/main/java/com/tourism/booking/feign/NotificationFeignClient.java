package com.tourism.booking.feign;

import com.tourism.booking.event.BookingEventDTO;
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

    /** Khách gửi yêu cầu hoàn tiền ngân hàng → email admin + WebSocket admin */
    @PostMapping("/api/notifications/refund-requested")
    void notifyRefundRequested(@RequestBody BookingEventDTO event);

    /** Booking bị hủy (coin path) → email khách + WebSocket */
    @PostMapping("/api/notifications/status-updated")
    void notifyStatusUpdated(@RequestBody BookingEventDTO event);
}

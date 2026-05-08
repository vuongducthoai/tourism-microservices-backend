package com.tourism.notification.controller;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.dto.UserStatusEventDTO;
import com.tourism.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints consumed by other services (via Feign).
 * booking-service → POST /api/notifications/refund-requested
 * booking-service → POST /api/notifications/status-updated
 * booking-service → POST /api/notifications/booking-confirmed
 * iam-service     → POST /api/notifications/user-status-updated
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @PostMapping("/refund-requested")
    public ResponseEntity<Void> refundRequested(@RequestBody BookingEventDTO event) {
        notificationService.handleRefundRequested(event);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/status-updated")
    public ResponseEntity<Void> statusUpdated(@RequestBody BookingEventDTO event) {
        notificationService.handleStatusUpdated(event);
        return ResponseEntity.ok().build();
    }

    /**
     * Admin xác nhận booking (PENDING_CONFIRMATION → PAID).
     */
    @PostMapping("/booking-confirmed")
    public ResponseEntity<Void> bookingConfirmed(@RequestBody BookingEventDTO event) {
        notificationService.handleBookingConfirmed(event);
        return ResponseEntity.ok().build();
    }

    /**
     * Admin khóa / mở khóa tài khoản user.
     * iam-service calls this → push WebSocket to /topic/admin/users.
     */
    @PostMapping("/user-status-updated")
    public ResponseEntity<Void> userStatusUpdated(@RequestBody UserStatusEventDTO event) {
        notificationService.handleUserStatusUpdated(event);
        return ResponseEntity.ok().build();
    }
}

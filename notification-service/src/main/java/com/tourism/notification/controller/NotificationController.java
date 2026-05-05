package com.tourism.notification.controller;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints consumed by booking-service (via Feign).
 * booking-service → POST /api/notifications/refund-requested
 * booking-service → POST /api/notifications/status-updated
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
}

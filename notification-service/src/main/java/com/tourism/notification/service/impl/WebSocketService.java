package com.tourism.notification.service.impl;

import com.tourism.notification.dto.BookingEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Mirrors monolith's WebSocketService.
 * Pushes booking event updates to:
 *   /topic/admin/bookings           — tất cả admin đang online
 *   /topic/user/{userId}/bookings   — user cụ thể
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /** Notify tất cả admin (dùng sau khi khách submit refund hoặc status thay đổi) */
    public void notifyAdminBookingUpdate(BookingEventDTO event) {
        try {
            messagingTemplate.convertAndSend("/topic/admin/bookings", event);
            log.info("WebSocket pushed to /topic/admin/bookings for booking: {}", event.getBookingCode());
        } catch (Exception e) {
            log.error("WebSocket admin push failed for booking {}: {}", event.getBookingCode(), e.getMessage());
        }
    }

    /** Notify user cụ thể (sau khi admin cập nhật trạng thái booking) */
    public void notifyUserBookingUpdate(Integer userId, BookingEventDTO event) {
        if (userId == null) return;
        try {
            messagingTemplate.convertAndSend("/topic/user/" + userId + "/bookings", event);
            log.info("WebSocket pushed to /topic/user/{}/bookings for booking: {}", userId, event.getBookingCode());
        } catch (Exception e) {
            log.error("WebSocket user push failed for userId={}, booking {}: {}",
                    userId, event.getBookingCode(), e.getMessage());
        }
    }
}

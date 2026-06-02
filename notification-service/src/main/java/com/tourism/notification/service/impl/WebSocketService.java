package com.tourism.notification.service.impl;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.dto.CouponEventDTO;
import com.tourism.notification.dto.UserStatusEventDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Mirrors monolith's WebSocketService.
 * Pushes booking event updates to:
 *   /topic/admin/bookings           — tất cả admin đang online
 *   /topic/user/{userId}/bookings   — user cụ thể
 *   /topic/admin/users              — admin user management page
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

    /**
     * Notify admin user management page when a user is locked/unlocked.
     * Mirrors monolith WebSocketService.notifyUserUpdate().
     * Frontend subscribes to /topic/admin/users → refetch() danh sách.
     */
    public void notifyAdminUserUpdate(UserStatusEventDTO event) {
        try {
            messagingTemplate.convertAndSend("/topic/admin/users", event);
            log.info("WebSocket pushed to /topic/admin/users for userId={}", event.getUserID());
        } catch (Exception e) {
            log.error("WebSocket push failed for /topic/admin/users userId={}: {}", event.getUserID(), e.getMessage());
        }
    }

    /** Notify admin coupon management page when a coupon is created/updated */
    public void notifyAdminCoupon(CouponEventDTO event) {
        try {
            messagingTemplate.convertAndSend("/topic/admin/coupons", event);
            log.info("WebSocket pushed to /topic/admin/coupons for coupon: {}", event.getCouponCode());
        } catch (Exception e) {
            log.error("WebSocket push failed for /topic/admin/coupons coupon={}: {}", event.getCouponCode(), e.getMessage());
        }
    }

    /** Broadcast promotion to all users (GLOBAL coupons) */
    public void broadcastPromotion(CouponEventDTO event) {
        try {
            messagingTemplate.convertAndSend("/topic/promotions", event);
            log.info("WebSocket broadcast to /topic/promotions for coupon: {}", event.getCouponCode());
        } catch (Exception e) {
            log.error("WebSocket broadcast failed for /topic/promotions coupon={}: {}", event.getCouponCode(), e.getMessage());
        }
    }

    /** Notify user khi trang thai lenh rut diem thay doi (created/completed) */
    public void notifyUserWithdrawalUpdate(Integer userId, BookingEventDTO event) {
        if (userId == null) return;
        try {
            messagingTemplate.convertAndSend("/topic/user/" + userId + "/withdrawals", event);
            log.info("WebSocket pushed to /topic/user/{}/withdrawals for ref: {}", userId, event.getReferenceCode());
        } catch (Exception e) {
            log.error("WebSocket withdrawal push failed for userId={}: {}", userId, e.getMessage());
        }
    }

    public void notifyUserForum(Integer userId, Object payload){
        if(userId == null) return;
        try {
            messagingTemplate.convertAndSend("/topic/user/" + userId + "/notifications", payload);
            log.info("WebSocket forum notification pushed to userId={}", userId); 
        } catch(Exception e){
             log.error("WebSocket push failed for userId={}: {}", userId, e.getMessage());
        }
    }
}

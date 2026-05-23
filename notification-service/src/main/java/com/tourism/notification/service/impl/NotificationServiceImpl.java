package com.tourism.notification.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.dto.CouponEventDTO;
import com.tourism.notification.dto.UserStatusEventDTO;
import com.tourism.notification.entity.Notification;
import com.tourism.notification.entity.NotificationType;
import com.tourism.notification.repository.NotificationRepository;
import com.tourism.notification.service.MailService;
import com.tourism.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final MailService            mailService;
    private final WebSocketService       webSocketService;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper           objectMapper;

    @Override
    public void handleRefundRequested(BookingEventDTO event) {
        log.info("Handling refund.requested for booking: {}", event.getBookingCode());

        mailService.sendRefundRequestNotification(event);

        saveNotification(
                null,
                NotificationType.BOOKING_REFUND_REQUESTED,
                "Yeu cau hoan tien moi",
                String.format("Booking %s cua khach %s yeu cau hoan tien ngan hang.",
                        event.getBookingCode(), event.getContactFullName())
        );

        if (event.getUserId() != null) {
            saveNotification(
                    event.getUserId(),
                    NotificationType.BOOKING_REFUND_REQUESTED,
                    "Yeu cau hoan tien da gui",
                    String.format("Booking %s cua ban dang cho hoan tien ngan hang.", event.getBookingCode()),
                    event.getBookingCode()
            );
        }

        webSocketService.notifyAdminBookingUpdate(event);
        webSocketService.notifyUserBookingUpdate(event.getUserId(), event);
    }

    @Override
    public void handleStatusUpdated(BookingEventDTO event) {
        log.info("Handling status.updated for booking: {} -> {}", event.getBookingCode(), event.getBookingStatus());

        String status = event.getBookingStatus();

        if ("CANCELLED".equals(status)) {
            boolean hasCoinRefund = event.getCoinRefundAmount() != null
                    && event.getCoinRefundAmount().compareTo(BigDecimal.ZERO) > 0;
            boolean hasBankRefund = event.getRefundAmount() != null
                    && event.getRefundAmount().compareTo(BigDecimal.ZERO) > 0;

            if (hasCoinRefund) {
                mailService.sendCancellationCoinEmail(event);
            } else if (hasBankRefund) {
                mailService.sendCancellationWithRefundEmail(event);
            } else {
                mailService.sendCancellationEmail(event);
            }

            mailService.sendCancellationAdminNotification(event);
        }

        webSocketService.notifyUserBookingUpdate(event.getUserId(), event);
        webSocketService.notifyAdminBookingUpdate(event);

        if (event.getUserId() != null) {
            saveNotification(
                    event.getUserId(),
                    NotificationType.BOOKING_CANCELLED,
                    "Trang thai booking cap nhat",
                    String.format("Booking %s da chuyen sang trang thai %s.",
                            event.getBookingCode(), status),
                    event.getBookingCode()
            );
        }
    }

    private void saveNotification(Integer userId, NotificationType type, String title, String message) {
        saveNotification(userId, type, title, message, null);
    }

    private void saveNotification(Integer userId, NotificationType type, String title, String message, String bookingCode) {
        try {
            Notification.NotificationBuilder builder = Notification.builder()
                    .userId(userId)
                    .type(type)
                    .title(title)
                    .message(message);
            if (bookingCode != null) {
                builder.metadata(objectMapper.valueToTree(Map.of("bookingCode", bookingCode)));
            }
            notificationRepository.save(builder.build());
        } catch (Exception e) {
            log.error("Failed to save notification: {}", e.getMessage());
        }
    }

    @Override
    public void handleBookingConfirmed(BookingEventDTO event) {
        log.info("Handling booking-confirmed for booking: {} -> PAID", event.getBookingCode());

        try {
            mailService.sendPaymentConfirmationEmail(event);
        } catch (Exception e) {
            log.error("Failed to send confirmation email for booking {}: {}",
                    event.getBookingCode(), e.getMessage());
        }

        webSocketService.notifyAdminBookingUpdate(event);

        if (event.getUserId() != null) {
            webSocketService.notifyUserBookingUpdate(event.getUserId(), event);
        }

        if (event.getUserId() != null) {
            saveNotification(
                    event.getUserId(),
                    NotificationType.BOOKING_CONFIRMED,
                    "Dat tour thanh cong",
                    String.format("Booking %s cua ban da duoc xac nhan va thanh toan thanh cong.",
                            event.getBookingCode()),
                    event.getBookingCode()
            );
        }
    }

    @Override
    public void handleUserStatusUpdated(UserStatusEventDTO event) {
        log.info("Handling user-status-updated for userId={}, status={}", event.getUserID(), event.getStatus());
        mailService.sendAccountStatusEmail(event);
        webSocketService.notifyAdminUserUpdate(event);
    }

    @Override
    public void handleCouponCreated(CouponEventDTO event) {
        log.info("Handling coupon-created for coupon: {}", event.getCouponCode());

        // Push to admin coupon management page
        webSocketService.notifyAdminCoupon(event);

        // Broadcast to users if GLOBAL coupon
        if ("GLOBAL".equals(event.getCouponType())) {
            webSocketService.broadcastPromotion(event);
        }

        // Save admin notification
        saveNotification(
                null,
                NotificationType.COUPON_NEW,
                "Coupon mới: " + event.getCouponCode(),
                String.format("Coupon '%s' giảm %d%% vừa được tạo.", event.getCouponCode(), event.getDiscountAmount())
        );
    }

    @Override
    public void handleCouponUpdated(CouponEventDTO event) {
        log.info("Handling coupon-updated for coupon: {}", event.getCouponCode());
        webSocketService.notifyAdminCoupon(event);
    }
}

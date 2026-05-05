package com.tourism.notification.service.impl;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.entity.Notification;
import com.tourism.notification.entity.NotificationType;
import com.tourism.notification.repository.NotificationRepository;
import com.tourism.notification.service.MailService;
import com.tourism.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final MailService            mailService;
    private final WebSocketService       webSocketService;
    private final NotificationRepository notificationRepository;

    @Override
    public void handleRefundRequested(BookingEventDTO event) {
        log.info("Handling refund.requested for booking: {}", event.getBookingCode());

        // 1. Email admin
        mailService.sendRefundRequestNotification(event);

        // 2. Lưu notification vào DB
        saveNotification(
                null,
                NotificationType.BOOKING_REFUND_REQUESTED,
                "Yêu cầu hoàn tiền mới",
                String.format("Booking %s của khách %s yêu cầu hoàn tiền ngân hàng.",
                        event.getBookingCode(), event.getContactFullName())
        );

        // 3. WebSocket → admin
        webSocketService.notifyAdminBookingUpdate(event);
    }

    @Override
    public void handleStatusUpdated(BookingEventDTO event) {
        log.info("Handling status.updated for booking: {} → {}", event.getBookingCode(), event.getBookingStatus());

        String status = event.getBookingStatus();

        // Email khách nếu hủy tour qua coin path
        if ("CANCELLED".equals(status) && event.getCoinRefundAmount() != null
                && event.getCoinRefundAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            mailService.sendCancellationCoinEmail(event);
        }

        // Email admin khi có bất kỳ hủy tour nào (CANCELLED)
        if ("CANCELLED".equals(status)) {
            mailService.sendCancellationAdminNotification(event);
        }

        // WebSocket → user + admin
        webSocketService.notifyUserBookingUpdate(event.getUserId(), event);
        webSocketService.notifyAdminBookingUpdate(event);

        // Lưu notification cho user
        if (event.getUserId() != null) {
            saveNotification(
                    event.getUserId(),
                    NotificationType.BOOKING_CANCELLED,
                    "Trạng thái booking cập nhật",
                    String.format("Booking %s đã chuyển sang trạng thái %s.",
                            event.getBookingCode(), status)
            );
        }
    }

    private void saveNotification(Integer userId, NotificationType type, String title, String message) {
        try {
            Notification n = Notification.builder()
                    .userId(userId)
                    .type(type)
                    .title(title)
                    .message(message)
                    .build();
            notificationRepository.save(n);
        } catch (Exception e) {
            log.error("Failed to save notification: {}", e.getMessage());
        }
    }
}

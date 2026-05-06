package com.tourism.notification.service;

import com.tourism.notification.dto.BookingEventDTO;

public interface MailService {
    /** Gửi email cho admin khi khách hàng yêu cầu hoàn tiền ngân hàng */
    void sendRefundRequestNotification(BookingEventDTO event);

    /** Gửi email xác nhận hủy tour cho khách hàng (coin path) */
    void sendCancellationCoinEmail(BookingEventDTO event);

    /** Gửi email xác nhận trạng thái booking cho khách hàng */
    void sendBookingStatusEmail(BookingEventDTO event);

    /** Gửi email thông báo hủy tour cho admin (coin path) */
    void sendCancellationAdminNotification(BookingEventDTO event);

    /**
     * Gửi email xác nhận đặt tour thành công khi admin confirm (PAID).
     * Được gọi bởi handleBookingConfirmed trong NotificationServiceImpl.
     */
    void sendPaymentConfirmationEmail(BookingEventDTO event);
}

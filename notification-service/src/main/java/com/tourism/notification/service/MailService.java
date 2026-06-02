package com.tourism.notification.service;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.dto.UserStatusEventDTO;
import com.tourism.notification.dto.VerificationEmailRequest;

public interface MailService {
    /** Gửi email cho admin khi khách hàng yêu cầu hoàn tiền ngân hàng */
    void sendRefundRequestNotification(BookingEventDTO event);

    /** Gửi email xác nhận hủy tour cho khách hàng (coin path) */
    void sendCancellationCoinEmail(BookingEventDTO event);

    /** Gui email huy tour cho khach hang khi khong co hoan tien */
    void sendCancellationEmail(BookingEventDTO event);

    /** Gui email huy tour kem thong tin hoan tien ngan hang cho khach hang */
    void sendCancellationWithRefundEmail(BookingEventDTO event);

    /** Gửi email xác nhận trạng thái booking cho khách hàng */
    void sendBookingStatusEmail(BookingEventDTO event);

    /** Gửi email thông báo hủy tour cho admin (coin path) */
    void sendCancellationAdminNotification(BookingEventDTO event);

    /**
     * Gửi email xác nhận đặt tour thành công khi admin confirm (PAID).
     * Được gọi bởi handleBookingConfirmed trong NotificationServiceImpl.
     */
    void sendPaymentConfirmationEmail(BookingEventDTO event);

    /**
     * Gửi email thông báo khóa / mở khóa tài khoản cho người dùng.
     * Được gọi bởi handleUserStatusUpdated trong NotificationServiceImpl.
     */
    void sendAccountStatusEmail(UserStatusEventDTO event);

    /** Gửi email xác thực tài khoản khi user đăng ký hoặc yêu cầu gửi lại */
    void sendVerificationEmail(VerificationEmailRequest request);

    /** Gửi email xác nhận cho user khi yêu cầu rút điểm được ghi nhận (MANUAL) */
    void sendCoinWithdrawalCreatedEmail(BookingEventDTO event);

    /** Gửi email xác nhận cho user khi lệnh rút điểm đã chuyển khoản thành công (COMPLETED) */
    void sendCoinWithdrawalCompletedEmail(BookingEventDTO event);
}


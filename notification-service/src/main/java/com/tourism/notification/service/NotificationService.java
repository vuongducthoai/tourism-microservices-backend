package com.tourism.notification.service;

import com.tourism.notification.dto.BookingEventDTO;

public interface NotificationService {

    /** Xử lý yêu cầu hoàn tiền ngân hàng: email admin + WebSocket admin */
    void handleRefundRequested(BookingEventDTO event);

    /** Xử lý cập nhật trạng thái booking: email khách (nếu có) + WebSocket */
    void handleStatusUpdated(BookingEventDTO event);

    /**
     * Admin xác nhận booking (PENDING_CONFIRMATION → PAID).
     * → Email xác nhận cho khách + WebSocket admin + WebSocket user
     */
    void handleBookingConfirmed(BookingEventDTO event);
}

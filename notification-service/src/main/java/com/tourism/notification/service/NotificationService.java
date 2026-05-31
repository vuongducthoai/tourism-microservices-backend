package com.tourism.notification.service;

import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.dto.CouponEventDTO;
import com.tourism.notification.dto.UserStatusEventDTO;

public interface NotificationService {

    void handleRefundRequested(BookingEventDTO event);

    void handleStatusUpdated(BookingEventDTO event);

    void handleBookingConfirmed(BookingEventDTO event);

    void handleCoinWithdrawal(BookingEventDTO event);

    void handleCoinWithdrawalFailed(BookingEventDTO event);

    void handleCoinWithdrawalManual(BookingEventDTO event);

    void handleUserStatusUpdated(UserStatusEventDTO event);

    void handleCouponCreated(CouponEventDTO event);

    void handleCouponUpdated(CouponEventDTO event);
}

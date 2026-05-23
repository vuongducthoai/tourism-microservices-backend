package com.tourism.notification.entity;

public enum NotificationType {
    // Booking
    BOOKING_CREATED,
    BOOKING_CONFIRMED,
    BOOKING_CANCELLED,
    BOOKING_REFUND_REQUESTED,
    BOOKING_REFUNDED,

    // Payment
    PAYMENT_SUCCESS,
    PAYMENT_FAILED,
    PAYMENT_REMINDER,

    // Tour
    TOUR_DEPARTURE_REMINDER,
    TOUR_UPDATED,

    // Promotion
    COUPON_NEW,
    COUPON_EXPIRING,

    // Forum
    POST_LIKED,
    POST_COMMENTED,
    POST_BOOKMARKED,
    NEW_FOLLOWER,

    // System
    SYSTEM_ANNOUNCEMENT,
    WELCOME,

    COMMENT_REPLIED,
    COMMENT_LIKED,
    NEW_POST_FROM_FOLLOWING,

}

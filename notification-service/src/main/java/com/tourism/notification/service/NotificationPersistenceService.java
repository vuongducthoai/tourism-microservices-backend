package com.tourism.notification.service;

import com.tourism.notification.entity.NotificationType;

public interface NotificationPersistenceService {

    void saveNotification(Integer userId, NotificationType type, String title, String message, String bookingCode);
}

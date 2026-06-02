package com.tourism.notification.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.notification.entity.Notification;
import com.tourism.notification.entity.NotificationType;
import com.tourism.notification.repository.NotificationRepository;
import com.tourism.notification.service.NotificationPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotificationPersistenceServiceImpl implements NotificationPersistenceService {

    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveNotification(Integer userId, NotificationType type, String title, String message, String bookingCode) {
        Notification.NotificationBuilder builder = Notification.builder()
                .userId(userId)
                .type(type)
                .title(title)
                .message(message);
        if (bookingCode != null) {
            builder.metadata(objectMapper.valueToTree(Map.of("bookingCode", bookingCode)));
        }
        notificationRepository.save(builder.build());
    }
}

package com.tourism.notification.controller;

import com.tourism.notification.entity.Notification;
import com.tourism.notification.repository.NotificationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "User Notifications", description = "Endpoints cho user xem và đọc thông báo")
public class UserNotificationController {

    private final NotificationRepository notificationRepository;

    @GetMapping
    @Operation(summary = "Lấy danh sách thông báo của user")
    public ResponseEntity<Page<Notification>> getNotifications(
            @RequestParam Integer userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<Notification> result = notificationRepository
                .findByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Đếm số thông báo chưa đọc của user")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@RequestParam Integer userId) {
        long count = notificationRepository.countByUserIdAndIsReadFalseAndIsDeletedFalse(userId);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PutMapping("/{notificationId}/read")
    @Operation(summary = "Đánh dấu một thông báo đã đọc")
    @Transactional
    public ResponseEntity<Void> markAsRead(@PathVariable Integer notificationId) {
        if (notificationId != null) {
            notificationRepository.findById(notificationId).ifPresent(n -> {
                n.setIsRead(true);
                notificationRepository.save(n);
            });
        }
        return ResponseEntity.ok().build();
    }

    @PutMapping("/read-all")
    @Operation(summary = "Đánh dấu tất cả thông báo đã đọc")
    @Transactional
    public ResponseEntity<Void> markAllRead(@RequestParam Integer userId) {
        notificationRepository.markAllReadByUserId(userId);
        return ResponseEntity.ok().build();
    }
}

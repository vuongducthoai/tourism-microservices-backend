package com.tourism.notification.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_notifications")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserNotification extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userNotificationID;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "is_seen", nullable = false)
    private Boolean isSeen = false;
}

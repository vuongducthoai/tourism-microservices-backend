package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Hạn chế user trong FORUM (cấm đăng bài/bình luận).
 * KHÔNG ảnh hưởng các tính năng khác (đặt tour, payment, profile...).
 */
@Entity
@Table(name = "forum_user_restrictions", indexes = {
        @Index(name = "idx_restriction_user", columnList = "user_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForumUserRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "banned_until")
    private LocalDateTime bannedUntil;   // null = cấm vĩnh viễn

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "banned_by")
    private Integer bannedBy;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

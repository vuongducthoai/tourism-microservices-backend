package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Khóa THƯỞNG COIN forum của user (PLAN_ADMIN_FORUM_COIN §3).
 * User vẫn like/comment/đăng bài bình thường — chỉ không nhận coin.
 * Khác với ForumUserRestriction (cấm hoạt động forum).
 */
@Entity
@Table(name = "forum_reward_restrictions", indexes = {
        @Index(name = "idx_reward_restriction_user", columnList = "user_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForumRewardRestriction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "banned_until")
    private LocalDateTime bannedUntil;   // null = khóa vĩnh viễn

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

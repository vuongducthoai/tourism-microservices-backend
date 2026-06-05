package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Cảnh báo bất thường về thưởng coin forum (PLAN_ADMIN_FORUM_COIN §5).
 * Sinh tự động bởi ForumRewardAlertService (scheduler) theo các rule anti-fraud.
 * dedupeKey UNIQUE → không sinh trùng cảnh báo cho cùng 1 sự kiện.
 */
@Entity
@Table(name = "forum_reward_alerts",
        uniqueConstraints = @UniqueConstraint(name = "uq_reward_alert_dedupe", columnNames = "dedupe_key"),
        indexes = @Index(name = "idx_reward_alert_status", columnList = "status, created_at"))
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForumRewardAlert {

    public enum AlertStatus { NEW, RESOLVED }

    public enum Severity { LOW, MEDIUM, HIGH }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã rule: CAP_STREAK / CROSS_LIKE / FOLLOW_BURST / SHORT_COMMENT / FAST_MILESTONE */
    @Column(name = "rule_code", nullable = false, length = 30)
    private String ruleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private Severity severity;

    /** User nghi vấn chính */
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    /** User liên quan (vd cặp like chéo) */
    @Column(name = "related_user_id")
    private Integer relatedUserId;

    /** postId/commentId liên quan nếu có */
    @Column(name = "ref_id")
    private Integer refId;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "dedupe_key", nullable = false, length = 150)
    private String dedupeKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 15)
    @Builder.Default
    private AlertStatus status = AlertStatus.NEW;

    @Column(name = "resolved_by")
    private Integer resolvedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Sổ audit mọi lần thưởng coin từ forum (PLAN_FORUM_COIN_REWARD §5.A.1).
 * operationKey UNIQUE → idempotent tuyệt đối, trùng với operationKey gửi sang IAM.
 * Bản ghi PENDING = đã quyết định thưởng nhưng chưa publish thành công sang IAM
 * (scheduler sẽ retry) — đóng vai trò mini-outbox.
 */
@Entity
@Table(name = "forum_coin_reward_logs",
        uniqueConstraints = @UniqueConstraint(name = "uq_forum_reward_operation_key", columnNames = "operation_key"),
        indexes = {
            @Index(name = "idx_forum_reward_user_created", columnList = "user_id, created_at"),
            @Index(name = "idx_forum_reward_status", columnList = "status")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ForumCoinRewardLog extends BaseEntity {

    public enum RewardAction { POST, COMMENT, LIKE_MILESTONE, COMMENT_LIKE_MILESTONE, FOLLOW, DAILY }

    public enum RewardStatus { PENDING, CREDITED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Người được thưởng (IAM userId) */
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 30)
    private RewardAction action;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "operation_key", nullable = false, length = 150)
    private String operationKey;

    @Column(name = "reason", length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RewardStatus status = RewardStatus.PENDING;

    /** postId / commentId / followerId tùy action — để admin truy vết */
    @Column(name = "ref_id")
    private Integer refId;

    // ── Thu hồi coin (admin clawback) ──
    @Column(name = "revoked_by")
    private Integer revokedBy;

    @Column(name = "revoked_at")
    private java.time.LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 255)
    private String revokeReason;
}

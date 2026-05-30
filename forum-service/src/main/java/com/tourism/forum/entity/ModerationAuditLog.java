package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Nhật ký kiểm duyệt — ghi lại mọi hành động admin/AI trên bài viết & bình luận.
 */
@Entity
@Table(name = "moderation_audit_logs", indexes = {
        @Index(name = "idx_audit_target", columnList = "target_type,target_id"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModerationAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20, nullable = false)
    private TargetType targetType;   // POST | COMMENT

    @Column(name = "target_id", nullable = false)
    private Integer targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 30, nullable = false)
    private AuditAction action;      // APPROVE, HIDE, DELETE, RESTORE, PIN, FEATURE, EDIT

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", length = 10, nullable = false)
    private ActorType actorType;     // ADMIN | AI

    @Column(name = "actor_id")
    private Integer actorId;         // null nếu AI

    @Column(name = "actor_email")
    private String actorEmail;

    @Column(name = "target_title", length = 500)
    private String targetTitle;      // tiêu đề bài / preview comment để dễ đọc log

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "old_value", length = 100)
    private String oldValue;

    @Column(name = "new_value", length = 100)
    private String newValue;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public enum TargetType { POST, COMMENT }
    public enum ActorType { ADMIN, AI }
    public enum AuditAction { APPROVE, HIDE, DELETE, RESTORE, PIN, UNPIN, FEATURE, UNFEATURE, EDIT, STATUS_CHANGE }
}

package com.tourism.forum.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Báo cáo nội dung vi phạm từ user (post hoặc comment).
 */
@Entity
@Table(name = "content_reports", indexes = {
        @Index(name = "idx_report_target", columnList = "target_type,target_id"),
        @Index(name = "idx_report_status", columnList = "status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 20, nullable = false)
    private ModerationAuditLog.TargetType targetType;  // POST | COMMENT (dùng lại enum)

    @Column(name = "target_id", nullable = false)
    private Integer targetId;

    @Column(name = "reporter_id", nullable = false)
    private Integer reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 30, nullable = false)
    private ReportReason reason;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "target_preview", length = 500)
    private String targetPreview;   // tiêu đề/nội dung để admin xem nhanh

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}

package com.tourism.forum.service;

import com.tourism.forum.config.AdminContext;
import com.tourism.forum.entity.ModerationAuditLog;
import com.tourism.forum.entity.ModerationAuditLog.*;
import com.tourism.forum.repository.ModerationAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Ghi nhật ký kiểm duyệt. Lấy danh tính admin từ AdminContext (ThreadLocal).
 * Fail-safe: lỗi ghi log KHÔNG được làm hỏng hành động chính.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final ModerationAuditLogRepository repository;

    public void logPost(AuditAction action, Integer postId, String title,
                        String reason, String oldValue, String newValue) {
        log(TargetType.POST, action, postId, title, reason, oldValue, newValue);
    }

    public void logComment(AuditAction action, Integer commentId, String preview,
                           String reason, String oldValue, String newValue) {
        log(TargetType.COMMENT, action, commentId, preview, reason, oldValue, newValue);
    }

    private void log(TargetType type, AuditAction action, Integer targetId, String title,
                     String reason, String oldValue, String newValue) {
        try {
            AdminContext.AdminIdentity admin = AdminContext.get();
            repository.save(ModerationAuditLog.builder()
                    .targetType(type)
                    .targetId(targetId)
                    .action(action)
                    .actorType(ActorType.ADMIN)
                    .actorId(admin != null ? admin.userId() : null)
                    .actorEmail(admin != null ? admin.email() : null)
                    .targetTitle(title != null && title.length() > 500 ? title.substring(0, 500) : title)
                    .reason(reason)
                    .oldValue(oldValue)
                    .newValue(newValue)
                    .build());
        } catch (Exception e) {
            log.warn("Ghi audit log thất bại (non-critical): {}", e.getMessage());
        }
    }
}

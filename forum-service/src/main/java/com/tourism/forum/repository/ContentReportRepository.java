package com.tourism.forum.repository;

import com.tourism.forum.entity.ContentReport;
import com.tourism.forum.entity.ModerationAuditLog;
import com.tourism.forum.entity.ReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentReportRepository extends JpaRepository<ContentReport, Long> {

    Page<ContentReport> findByStatusOrderByCreatedAtDesc(ReportStatus status, Pageable pageable);

    Page<ContentReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(ReportStatus status);

    // Chống 1 user report trùng 1 nội dung nhiều lần
    boolean existsByReporterIdAndTargetTypeAndTargetId(
            Integer reporterId, ModerationAuditLog.TargetType targetType, Integer targetId);

    // Có report đang chờ xử lý trên nội dung này? — hoãn thưởng coin nếu có
    boolean existsByTargetTypeAndTargetIdAndStatus(
            ModerationAuditLog.TargetType targetType, Integer targetId, ReportStatus status);
}

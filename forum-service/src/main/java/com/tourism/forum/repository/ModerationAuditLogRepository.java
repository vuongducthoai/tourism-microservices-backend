package com.tourism.forum.repository;

import com.tourism.forum.entity.ModerationAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ModerationAuditLogRepository
        extends JpaRepository<ModerationAuditLog, Long>, JpaSpecificationExecutor<ModerationAuditLog> {

    Page<ModerationAuditLog> findByTargetTypeAndTargetId(
            ModerationAuditLog.TargetType targetType, Integer targetId, Pageable pageable);

    List<ModerationAuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
            LocalDateTime from, LocalDateTime to);

    /** Top admin theo số action — limit ở caller (LIMIT clause của Pageable không hợp với native Hibernate group-by trên Pageable, dùng JPQL + Pageable). */
    @Query("SELECT a.actorEmail, a.action, COUNT(a) FROM ModerationAuditLog a " +
           "WHERE a.actorType = com.tourism.forum.entity.ModerationAuditLog$ActorType.ADMIN " +
           "AND a.createdAt BETWEEN :from AND :to " +
           "GROUP BY a.actorEmail, a.action")
    List<Object[]> countByAdminAndAction(LocalDateTime from, LocalDateTime to);
}

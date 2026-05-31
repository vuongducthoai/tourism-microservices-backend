package com.tourism.booking.repository;

import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claim a batch of NEW events that are due for processing.
     * Uses FOR UPDATE SKIP LOCKED so multiple instances never claim the same row.
     * Caller MUST be inside a @Transactional method — the lock is held until
     * the transaction commits.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'NEW'
              AND next_retry_at <= :now
            ORDER BY created_at ASC
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findAndLockPending(
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize);

    /** Used in tests / monitoring */
    List<OutboxEvent> findByStatus(OutboxStatus status);

    /** Dùng cho Admin API: list DEAD events theo routingKey */
    List<OutboxEvent> findByStatusAndRoutingKey(OutboxStatus status, String routingKey);

    /** Dùng cho Admin API: paginated DEAD list, mới nhất lên trước */
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'DEAD' ORDER BY o.createdAt DESC")
    Page<OutboxEvent> findDeadEvents(Pageable pageable);

    boolean existsByIdempotencyKey(String idempotencyKey);

        java.util.Optional<OutboxEvent> findByIdempotencyKey(String idempotencyKey);

    /** Count DEAD rows — used for health check / alerting */
    long countByStatus(OutboxStatus status);

    @Transactional
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.status = 'NEW', o.lockedBy = null, o.lockedAt = null " +
           "WHERE o.status = 'SENDING' AND o.lockedAt < :cutoff")
    int resetStaleLocks(@Param("cutoff") LocalDateTime cutoff);
}

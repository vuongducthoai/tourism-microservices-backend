package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Outbox table for guaranteed at-least-once delivery to RabbitMQ.
 * Scheduler reads NEW rows, publishes to exchange, marks SENT.
 * Multi-instance safe via FOR UPDATE SKIP LOCKED.
 *
 * Status machine:
 *   NEW → SENDING → SENT  (success)
 *   SENDING → NEW          (retry after backoff)
 *   NEW → DEAD             (after max_retries exceeded)
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = @Index(name = "idx_outbox_pending", columnList = "status, next_retry_at")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique key = bookingCode_eventType_epochMs — guarantees idempotency on consumer side */
    @Column(name = "idempotency_key", unique = true, nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "exchange", nullable = false, length = 100)
    private String exchange;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    /** JSON payload — serialised BookingEventDTO */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /**
     * NEW     – waiting to be published
     * SENDING – locked by a scheduler instance, being processed
     * SENT    – successfully acked by broker
     * DEAD    – exceeded max_retries; needs manual intervention
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.NEW;

    @Column(name = "retries", nullable = false)
    @Builder.Default
    private int retries = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private int maxRetries = 5;

    /** Maximum backoff per retry attempt in seconds (default 1 hour = 3600s) */
    @Column(name = "max_backoff_secs", nullable = false, columnDefinition = "bigint not null default 3600")
    @Builder.Default
    private long maxBackoffSecs = 3600L;

    /** Hostname:PID of the scheduler instance holding the lock */
    @Column(name = "locked_by", length = 150)
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /** Earliest time this row may be retried (exponential back-off) */
    @Column(name = "next_retry_at", nullable = false)
    @Builder.Default
    private LocalDateTime nextRetryAt = LocalDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // ── helpers ──────────────────────────────────────────────────────────────

    public void incrementRetries(String error) {
        this.retries++;
        this.errorMessage = error;
        if (this.retries >= this.maxRetries) {
            this.status = OutboxStatus.DEAD;
        } else {
            long backoffSecs = Math.min(
                (long) Math.pow(2, this.retries) * 30L,
                this.maxBackoffSecs   // cap: never exceed maxBackoffSecs per attempt
            );
            this.nextRetryAt = LocalDateTime.now().plusSeconds(backoffSecs);
            this.status = OutboxStatus.NEW;
        }
        this.lockedBy = null;
        this.lockedAt = null;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = LocalDateTime.now();
        this.lockedBy = null;
        this.lockedAt = null;
    }
}

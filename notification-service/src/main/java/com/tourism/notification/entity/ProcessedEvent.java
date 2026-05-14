package com.tourism.notification.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Stores idempotency keys for processed RabbitMQ events.
 * Before processing a message, BookingEventListener checks this table.
 * If the key exists → skip (already processed).
 */
@Entity
@Table(
    name = "processed_events",
    indexes = @Index(name = "idx_processed_key", columnList = "idempotency_key")
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", unique = true, nullable = false, length = 150)
    private String idempotencyKey;

    @Column(name = "event_type", length = 50)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    @Builder.Default
    private LocalDateTime processedAt = LocalDateTime.now();
}

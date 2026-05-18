package com.tourism.iam.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Records every coin credit operation with an idempotency key.
 * Unique constraint on operationKey prevents double-credit even if
 * CoinRefundRelayScheduler delivers the same event twice.
 */
@Entity
@Table(name = "coin_transactions",
        uniqueConstraints = @UniqueConstraint(name = "uq_coin_operation_key", columnNames = "operation_key"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CoinTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_key", nullable = false, length = 150)
    private String operationKey;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** CREDIT or DEBIT — currently always CREDIT */
    @Column(nullable = false, length = 10)
    private String direction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void prePersist() {
        createdAt = LocalDateTime.now();
    }
}

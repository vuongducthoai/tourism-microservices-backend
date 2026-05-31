package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "coin_withdrawals",
        indexes = {
                @Index(name = "idx_coin_withdrawals_user_created", columnList = "user_id, created_at"),
                @Index(name = "idx_coin_withdrawals_status", columnList = "status"),
                @Index(name = "idx_coin_withdrawals_operation_key", columnList = "operation_key", unique = true)
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoinWithdrawal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_code", nullable = false, unique = true, length = 50)
    private String referenceCode;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "coin_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal coinAmount;

    @Column(name = "money_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal moneyAmount;

    @Column(name = "bank", nullable = false, length = 120)
    private String bank;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "account_name", nullable = false, length = 150)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CoinWithdrawalStatus status;

    @Column(name = "transfer_ref", length = 150)
    private String transferRef;

    @Column(name = "operation_key", nullable = false, unique = true, length = 150)
    private String operationKey;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_source", length = 30)
    private CoinWithdrawalErrorSource errorSource;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }
}
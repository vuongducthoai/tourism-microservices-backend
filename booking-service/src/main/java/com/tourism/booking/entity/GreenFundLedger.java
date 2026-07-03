package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sổ tổng Quỹ Xanh (PLAN_GREEN_FUND_TRONG_CAY §6.A.1) — bảng singleton 1 dòng (id=1).
 * pendingFund (quỹ chưa quy đổi) = totalFundRaised - convertedFund.
 */
@Entity
@Table(name = "green_fund_ledger")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GreenFundLedger {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    /** Tổng quỹ đã gom (VND) — booking + donation */
    @Column(name = "total_fund_raised", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal totalFundRaised = BigDecimal.ZERO;

    /** Phần quỹ đã quy đổi thành cây (VND) */
    @Column(name = "converted_fund", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal convertedFund = BigDecimal.ZERO;

    /** Tổng số cây đã trồng (quy đổi từ quỹ) */
    @Column(name = "trees_planted", nullable = false)
    @Builder.Default
    private Long treesPlanted = 0L;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

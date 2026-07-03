package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Một lượt đóng góp vào Quỹ Xanh (PLAN_GREEN_FUND_TRONG_CAY §6.A.2).
 * Nguồn: BOOKING (trích % doanh thu, không thu thêm tiền khách) hoặc DONATION (user góp coin).
 * operationKey UNIQUE → idempotent tuyệt đối (booking chỉ đóng 1 lần, retry không double-count).
 */
@Entity
@Table(name = "green_fund_contributions",
        uniqueConstraints = @UniqueConstraint(name = "uq_greenfund_operation_key", columnNames = "operation_key"),
        indexes = {
            @Index(name = "idx_greenfund_user", columnList = "user_id, created_at"),
            @Index(name = "idx_greenfund_created", columnList = "created_at")
        })
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GreenFundContribution {

    public enum Source { BOOKING, DONATION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 15)
    private Source source;

    /** Người đóng góp (IAM userId) — booking có thể null nếu đặt không đăng nhập */
    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "booking_code", length = 20)
    private String bookingCode;

    /** Số coin user góp (chỉ với DONATION) */
    @Column(name = "coin_amount", precision = 19, scale = 2)
    private BigDecimal coinAmount;

    /** Giá trị đóng góp quy ra VND */
    @Column(name = "amount_vnd", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountVnd;

    @Column(name = "operation_key", nullable = false, length = 150)
    private String operationKey;

    /** User chọn ẩn danh trên bảng vinh danh/lịch sử công khai */
    @Column(name = "anonymous", nullable = false)
    @Builder.Default
    private Boolean anonymous = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

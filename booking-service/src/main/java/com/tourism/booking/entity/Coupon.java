package com.tourism.booking.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "coupons")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Coupon extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer couponID;

    @Column(unique = true, nullable = false, length = 50)
    @NotBlank
    @Pattern(regexp = "^[A-Z0-9]+$")
    private String couponCode;

    @Column(columnDefinition = "TEXT")
    private String description;

    @NotNull @Min(1)
    private Integer discountAmount;

    @Column(name = "start_date")
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "usage_count")
    private Integer usageCount = 0;

    @Column(name = "min_order_value")
    private BigDecimal minOrderValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "coupon_type", nullable = false)
    private CouponType couponType = CouponType.GLOBAL;

    // Tham chiếu sang Tour Catalog Service bằng ID
    @Column(name = "departure_id")
    private Integer departureId;

    public boolean isValid() {
        LocalDateTime now = LocalDateTime.now();
        return !Boolean.TRUE.equals(getIsDeleted()) &&
                (startDate == null || now.isAfter(startDate)) &&
                (endDate == null || now.isBefore(endDate)) &&
                (usageLimit == null || usageCount < usageLimit);
    }
}

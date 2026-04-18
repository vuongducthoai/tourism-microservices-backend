package com.tourism.booking.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Booking extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer bookingID;

    @Column(name = "booking_code", unique = true, nullable = false, length = 20)
    private String bookingCode;

    @PrePersist
    public void generateBookingCode() {
        if (this.bookingCode == null) {
            this.bookingCode = "BK" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    @NotNull
    private LocalDateTime bookingDate = LocalDateTime.now();

    @NotBlank @Email
    private String contactEmail;

    @NotBlank
    private String contactFullName;

    @NotBlank
    private String contactPhone;

    @NotBlank
    private String contactAddress;

    @Column(columnDefinition = "TEXT")
    private String customerNote;

    @Min(1)
    private Integer totalPassengers;

    @NotNull @Min(0)
    private BigDecimal subtotalPrice;

    private BigDecimal surcharge;

    @Column(name = "coupon_discount", nullable = false)
    private BigDecimal couponDiscount = BigDecimal.ZERO;

    @Column(name = "paid_by_coin", nullable = false)
    private BigDecimal paidByCoin = BigDecimal.ZERO;

    @NotNull @Min(0)
    private BigDecimal totalPrice;

    @Column(columnDefinition = "TEXT")
    private String cancelReason;

    private BigDecimal refundAmount;

    @Enumerated(EnumType.STRING)
    private BookingStatus bookingStatus;

    // Tham chiếu sang IAM Service bằng ID
    @Column(name = "user_id")
    private Integer userId;

    // Tham chiếu sang Tour Catalog Service bằng ID
    @Column(name = "departure_id", nullable = false)
    private Integer departureId;

    @Column(name = "applied_coupon_codes", columnDefinition = "TEXT")
    private String appliedCouponCodes;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<BookingPassenger> passengers;

    @OneToOne(mappedBy = "booking", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private RefundInformation refundInformation;
}

package com.tourism.payment.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Payment extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer paymentID;

    @Enumerated(EnumType.STRING)
    private PaymentMethod paymentMethod;

    @NotNull
    private BigDecimal amount;

    private String transactionId;
    private String bankTransactionNo;
    private String bankCode;
    private LocalDateTime paymentDate;

    @Column(name = "account_name")
    private String accountName;

    @Column(name = "account_number")
    private String accountNumber;

    @Column(name = "bank_name")
    private String bank;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(name = "time_limit")
    private LocalDateTime timeLimit;

    @Column(name = "payment_description", length = 500)
    private String paymentDescription;

    @Column(name = "transaction_datetime")
    private LocalDateTime transactionDatetime;

    // Tham chiếu sang Booking Service bằng ID (không dùng JPA relationship cross-service)
    @Column(name = "booking_id", unique = true, nullable = false)
    private Integer bookingId;
}

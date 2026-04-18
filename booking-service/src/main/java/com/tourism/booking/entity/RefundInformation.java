package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_information")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundInformation extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer refundID;

    private String bankName;
    private String accountNumber;
    private String accountName;
    private BigDecimal refundAmount;
    private String refundStatus;
    private LocalDateTime refundDate;
    private String note;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", unique = true)
    private Booking booking;
}

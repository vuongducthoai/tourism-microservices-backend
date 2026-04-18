package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "booking_passengers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingPassenger extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer passengerID;

    private String fullName;
    private String phone;

    @Enumerated(EnumType.STRING)
    private PassengerType passengerType;

    private BigDecimal price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;
}

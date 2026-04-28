package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

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

    private String gender;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Enumerated(EnumType.STRING)
    private PassengerType passengerType;

    @Column(name = "base_price")
    private BigDecimal basePrice;

    @Column(name = "requires_single_room")
    private Boolean requiresSingleRoom = false;

    @Column(name = "single_room_surcharge")
    private BigDecimal singleRoomSurcharge;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;
}

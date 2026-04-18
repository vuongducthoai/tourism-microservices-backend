package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "tour_departures")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TourDeparture extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer departureID;

    @Column(name = "departure_code", unique = true, nullable = false)
    private String departureCode;

    @Column(name = "departure_date", nullable = false)
    private LocalDate departureDate;

    @Column(name = "return_date")
    private LocalDate returnDate;

    @Column(name = "available_slots")
    private Integer availableSlots;

    @Column(name = "total_slots")
    private Integer totalSlots;

    private Boolean status = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id", nullable = false)
    private Tour tour;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policy_template_id")
    private PolicyTemplate policyTemplate;

    @OneToMany(mappedBy = "tourDeparture", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DeparturePricing> pricings;

    @OneToMany(mappedBy = "tourDeparture", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DepartureTransport> transports;
}

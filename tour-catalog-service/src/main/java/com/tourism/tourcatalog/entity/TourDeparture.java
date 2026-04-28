package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
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

    @Column(name = "departure_date", nullable = false)
    private LocalDateTime departureDate;

    @Column(name = "available_slots")
    private Integer availableSlots;

    @Column(name = "tour_guide_info", columnDefinition = "TEXT", nullable = false)
    private String tourGuideInfo;

    private Boolean status = true;

    // Tham chiếu sang Booking Service bằng ID (không dùng JPA relationship cross-service)
    @Column(name = "coupon_id")
    private Integer couponId;

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

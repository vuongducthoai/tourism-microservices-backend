package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "departure_pricings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeparturePricing extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer pricingID;

    private String passengerType;
    private BigDecimal price;
    private BigDecimal originalPrice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "departure_id")
    private TourDeparture tourDeparture;
}

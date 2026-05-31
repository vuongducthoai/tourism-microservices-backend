package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Pin trên bản đồ lộ trình tour (Leaflet). Mỗi row = 1 điểm dừng.
 * Có thể gắn vào ItineraryDay (nullable) để filter theo ngày trên FE.
 */
@Entity
@Table(name = "tour_stops", indexes = {
        @Index(name = "idx_stop_tour", columnList = "tour_id"),
        @Index(name = "idx_stop_day",  columnList = "itinerary_day_id")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TourStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer stopId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id", nullable = false)
    private Tour tour;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "itinerary_day_id")
    private ItineraryDay itineraryDay;

    @Column(nullable = false)
    private String name;

    @Column(name = "lat", nullable = false)
    private Double latitude;

    @Column(name = "lng", nullable = false)
    private Double longitude;

    @Column(name = "stop_order", nullable = false)
    private Integer stopOrder;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "stop_type", length = 30)
    private String stopType;   // ATTRACTION | HOTEL | RESTAURANT | TRANSPORT | START | END
}

package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "departure_transports")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DepartureTransport extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer transportID;

    @Enumerated(EnumType.STRING)
    private TransportType transportType;

    @Enumerated(EnumType.STRING)
    private VehicleType vehicleType;

    private String departureLocation;
    private String arrivalLocation;
    private String departureTime;
    private String arrivalTime;
    private String transportCode;
    private String note;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "departure_id")
    private TourDeparture tourDeparture;
}

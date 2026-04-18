package com.tourism.tourcatalog.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tours")
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"images", "itineraryDays", "departures"})
public class Tour extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer tourID;

    @Column(name = "tour_code", length = 50, unique = true, nullable = false)
    @NotBlank
    @Pattern(regexp = "^[A-Z0-9-]+$")
    private String tourCode;

    @Column(name = "tour_name", nullable = false)
    @NotBlank @Size(min = 10, max = 255)
    private String tourName;

    @NotBlank
    private String duration;

    @NotBlank
    private String transportation;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "start_location_id", nullable = false)
    private Location startLocation;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "end_location_id", nullable = false)
    private Location endLocation;

    @Column(columnDefinition = "TEXT")
    @NotBlank
    private String attractions;

    @Column(columnDefinition = "TEXT")
    private String meals;

    @Column(name = "ideal_time")
    private String idealTime;

    @Column(name = "status")
    private Boolean status = true;

    @Column(name = "trip_transportation")
    private String tripTransportation;

    @Column(name = "suitable_customer")
    private String suitableCustomer;

    private String hotel;

    @OneToMany(mappedBy = "tour", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TourImage> images;

    @OneToMany(mappedBy = "tour", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TourMedia> mediaList = new ArrayList<>();

    @OneToMany(mappedBy = "tour", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ItineraryDay> itineraryDays;

    @OneToMany(mappedBy = "tour", cascade = CascadeType.ALL)
    @JsonIgnore
    private List<TourDeparture> departures;
}

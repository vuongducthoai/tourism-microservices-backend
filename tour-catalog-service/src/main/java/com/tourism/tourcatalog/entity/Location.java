package com.tourism.tourcatalog.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "locations")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Location extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer locationID;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Region region;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "airport_code", length = 10)
    private String airportCode;

    @Column(name = "airport_name")
    private String airportName;

    @OneToMany(mappedBy = "startLocation")
    @JsonIgnore
    private List<Tour> startPoint;

    @OneToMany(mappedBy = "endLocation")
    @JsonIgnore
    private List<Tour> endPoint;

    @Column(name = "status")
    private boolean status = true;
}

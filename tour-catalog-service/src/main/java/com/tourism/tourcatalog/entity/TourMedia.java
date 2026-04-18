package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tour_media")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TourMedia extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer tourMediaID;

    private String mediaUrl;
    private String mediaType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id")
    private Tour tour;
}

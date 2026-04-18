package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "favorite_tours")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FavoriteTour extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer favoriteID;

    // Tham chiếu sang IAM Service bằng ID
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id", nullable = false)
    private Tour tour;
}

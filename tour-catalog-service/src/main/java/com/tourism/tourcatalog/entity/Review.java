package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "reviews")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Review extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer reviewID;

    @NotNull @Min(1) @Max(5)
    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String comment;

    private Boolean isVisible = true;

    // Tham chiếu sang Booking Service bằng ID
    @Column(name = "booking_id", nullable = false, unique = true)
    private Integer bookingId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tour_id", nullable = false)
    private Tour tour;

    // Tham chiếu sang IAM Service bằng ID
    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @OneToMany(mappedBy = "review", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ImageReview> images;
}

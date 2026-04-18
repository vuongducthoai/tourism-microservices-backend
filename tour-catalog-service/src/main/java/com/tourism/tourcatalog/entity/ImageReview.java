package com.tourism.tourcatalog.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "image_reviews")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageReview extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer imageReviewID;

    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id")
    private Review review;
}

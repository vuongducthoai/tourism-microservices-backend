package com.tourism.booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Đợt trồng cây THẬT (PLAN_GREEN_FUND_TRONG_CAY §6.A.3) — admin nhập sau mỗi đợt
 * (địa điểm, ngày, số cây, ảnh) để dashboard minh bạch, tránh "trồng cây ảo".
 */
@Entity
@Table(name = "tree_planting_batches")
@Getter @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TreePlantingBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "location", nullable = false, length = 255)
    private String location;

    @Column(name = "planted_date")
    private LocalDate plantedDate;

    @Column(name = "tree_count", nullable = false)
    private Integer treeCount;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}

package com.tourism.analytics.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "chatbot_vector_sync_runs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ChatbotVectorSyncRun extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String triggerType;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
    private Long durationMs;

    private Integer tourDocs;
    private Integer locationDocs;
    private Integer reviewDocs;
    private Integer couponDocs;
    private Integer totalDocs;
    private Integer eventCount;

    @Column(length = 300)
    private String entityTypes;

    @Column(length = 2000)
    private String errorMessage;
}

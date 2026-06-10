package com.tourism.analytics.dto.sync;

import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorSyncRunDTO {
    private Long id;
    private String triggerType;
    private String status;
    private String startedAt;
    private String finishedAt;
    private Long durationMs;
    private Integer tourDocs;
    private Integer locationDocs;
    private Integer reviewDocs;
    private Integer couponDocs;
    private Integer totalDocs;
    private Integer eventCount;
    private String entityTypes;
    private String errorMessage;
}

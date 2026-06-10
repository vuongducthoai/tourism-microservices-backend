package com.tourism.analytics.dto.sync;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatbotSyncEventDTO {
    private String eventId;
    private String sourceService;
    private String entityType;
    private Integer entityId;
    private Integer parentTourId;
    private String operation;
    private String occurredAt;
}

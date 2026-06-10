package com.tourism.analytics.dto.sync;

import lombok.*;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorSyncSummaryDTO {
    private String from;
    private String to;
    private long todaySyncCount;
    private long successCount;
    private long failedCount;
    private long pendingEventCount;
    private boolean syncRunning;
    private VectorSyncRunDTO lastRun;
    private List<VectorSyncRunDTO> recentRuns;
}

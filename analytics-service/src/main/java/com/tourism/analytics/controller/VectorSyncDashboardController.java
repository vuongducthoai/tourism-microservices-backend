package com.tourism.analytics.controller;

import com.tourism.analytics.dto.sync.VectorSyncSummaryDTO;
import com.tourism.analytics.service.ChatbotSyncDebounceService;
import com.tourism.analytics.service.ChatbotVectorSyncRunService;
import com.tourism.analytics.service.VectorService;
import com.tourism.analytics.service.VectorSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard/vector-sync")
@RequiredArgsConstructor
@Slf4j
public class VectorSyncDashboardController {

    private final ChatbotVectorSyncRunService syncRunService;
    private final ChatbotSyncDebounceService debounceService;
    private final VectorSyncService vectorSyncService;
    private final VectorService vectorService;

    @GetMapping("/summary")
    public ResponseEntity<VectorSyncSummaryDTO> summary(
            @RequestParam(required = false, defaultValue = "false") boolean all,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (all) {
            return ResponseEntity.ok(syncRunService.summaryAll(
                    debounceService.pendingCount(),
                    vectorSyncService.isSyncRunning()
            ));
        }
        return ResponseEntity.ok(syncRunService.summary(
                from,
                to,
                debounceService.pendingCount(),
                vectorSyncService.isSyncRunning()
        ));
    }

    @PostMapping("/manual-sync")
    public ResponseEntity<Map<String, Object>> manualSync() {
        VectorSyncService.SyncCounts counts = vectorSyncService.syncAllDetailed("MANUAL");
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Đồng bộ chatbot hoàn tất",
                "totalDocs", counts.getTotalDocs(),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    @DeleteMapping("/manual-clear")
    public ResponseEntity<Map<String, Object>> manualClear() {
        log.warn("Admin triggered chatbot vector clear from dashboard");
        vectorService.deleteAll();
        syncRunService.recordClearSuccess();
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Đã xoá toàn bộ vector chatbot. Hãy bấm Sync ngay để nạp lại dữ liệu.",
                "timestamp", LocalDateTime.now().toString()
        ));
    }
}

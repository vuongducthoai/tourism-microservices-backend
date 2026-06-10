package com.tourism.analytics.service;

import com.tourism.analytics.dto.sync.VectorSyncRunDTO;
import com.tourism.analytics.dto.sync.VectorSyncSummaryDTO;
import com.tourism.analytics.entity.ChatbotVectorSyncRun;
import com.tourism.analytics.repository.ChatbotVectorSyncRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatbotVectorSyncRunService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private final ChatbotVectorSyncRunRepository repository;

    public ChatbotVectorSyncRun start(String triggerType, int eventCount, String entityTypes) {
        ChatbotVectorSyncRun run = ChatbotVectorSyncRun.builder()
                .triggerType(triggerType)
                .status(STATUS_RUNNING)
                .startedAt(LocalDateTime.now())
                .eventCount(eventCount)
                .entityTypes(entityTypes)
                .tourDocs(0)
                .locationDocs(0)
                .reviewDocs(0)
                .couponDocs(0)
                .totalDocs(0)
                .build();
        return repository.save(run);
    }

    public ChatbotVectorSyncRun markSuccess(ChatbotVectorSyncRun run, VectorSyncService.SyncCounts counts) {
        LocalDateTime finishedAt = LocalDateTime.now();
        run.setStatus(STATUS_SUCCESS);
        run.setFinishedAt(finishedAt);
        run.setDurationMs(Duration.between(run.getStartedAt(), finishedAt).toMillis());
        if (counts != null) {
            run.setTourDocs(counts.getTourDocs());
            run.setLocationDocs(counts.getLocationDocs());
            run.setReviewDocs(counts.getReviewDocs());
            run.setCouponDocs(counts.getCouponDocs());
            run.setTotalDocs(counts.getTotalDocs());
        }
        run.setErrorMessage(null);
        return repository.save(run);
    }

    public ChatbotVectorSyncRun markFailed(ChatbotVectorSyncRun run, Exception error) {
        LocalDateTime finishedAt = LocalDateTime.now();
        run.setStatus(STATUS_FAILED);
        run.setFinishedAt(finishedAt);
        run.setDurationMs(Duration.between(run.getStartedAt(), finishedAt).toMillis());
        run.setErrorMessage(shorten(error != null ? error.getMessage() : "Unknown sync error"));
        return repository.save(run);
    }

    public ChatbotVectorSyncRun recordClearSuccess() {
        ChatbotVectorSyncRun run = start("CLEAR", 0, "ALL");
        return markSuccess(run, VectorSyncService.SyncCounts.empty());
    }

    public VectorSyncSummaryDTO summary(LocalDate from, LocalDate to, long pendingEventCount, boolean syncRunning) {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo;
        LocalDateTime fromAt = effectiveFrom.atStartOfDay();
        LocalDateTime toAt = effectiveTo.plusDays(1).atStartOfDay().minusNanos(1);

        List<VectorSyncRunDTO> recentRuns = repository
                .findByStartedAtBetweenOrderByStartedAtDesc(fromAt, toAt, PageRequest.of(0, 10))
                .stream()
                .map(this::toDto)
                .toList();

        return VectorSyncSummaryDTO.builder()
                .from(effectiveFrom.toString())
                .to(effectiveTo.toString())
                .todaySyncCount(repository.countByStartedAtBetween(fromAt, toAt))
                .successCount(repository.countByStatusAndStartedAtBetween(STATUS_SUCCESS, fromAt, toAt))
                .failedCount(repository.countByStatusAndStartedAtBetween(STATUS_FAILED, fromAt, toAt))
                .pendingEventCount(pendingEventCount)
                .syncRunning(syncRunning)
                .lastRun(repository.findTopByOrderByStartedAtDesc().map(this::toDto).orElse(null))
                .recentRuns(recentRuns)
                .build();
    }

    public VectorSyncSummaryDTO summaryAll(long pendingEventCount, boolean syncRunning) {
        List<VectorSyncRunDTO> runs = repository.findAllByOrderByStartedAtDesc()
                .stream()
                .map(this::toDto)
                .toList();

        return VectorSyncSummaryDTO.builder()
                .from("ALL")
                .to("ALL")
                .todaySyncCount(repository.count())
                .successCount(repository.countByStatus(STATUS_SUCCESS))
                .failedCount(repository.countByStatus(STATUS_FAILED))
                .pendingEventCount(pendingEventCount)
                .syncRunning(syncRunning)
                .lastRun(repository.findTopByOrderByStartedAtDesc().map(this::toDto).orElse(null))
                .recentRuns(runs)
                .build();
    }

    public VectorSyncRunDTO toDto(ChatbotVectorSyncRun run) {
        if (run == null) return null;
        return VectorSyncRunDTO.builder()
                .id(run.getId())
                .triggerType(run.getTriggerType())
                .status(run.getStatus())
                .startedAt(run.getStartedAt() != null ? run.getStartedAt().toString() : null)
                .finishedAt(run.getFinishedAt() != null ? run.getFinishedAt().toString() : null)
                .durationMs(run.getDurationMs())
                .tourDocs(run.getTourDocs())
                .locationDocs(run.getLocationDocs())
                .reviewDocs(run.getReviewDocs())
                .couponDocs(run.getCouponDocs())
                .totalDocs(run.getTotalDocs())
                .eventCount(run.getEventCount())
                .entityTypes(run.getEntityTypes())
                .errorMessage(run.getErrorMessage())
                .build();
    }

    private String shorten(String value) {
        if (value == null) return null;
        return value.length() <= 1800 ? value : value.substring(0, 1800);
    }
}

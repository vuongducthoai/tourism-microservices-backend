package com.tourism.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.analytics.dto.sync.ChatbotSyncEventDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatbotSyncDebounceService {

    private static final String PENDING_PREFIX = "chatbot-sync:pending:";
    private static final Duration PENDING_TTL = Duration.ofHours(24);
    private static final Duration QUIET_WINDOW = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final VectorIncrementalSyncService incrementalSyncService;
    private final VectorSyncService vectorSyncService;

    public void enqueue(ChatbotSyncEventDTO event) {
        if (event == null || event.getEntityType() == null || event.getEntityId() == null) return;
        String key = keyFor(event);
        try {
            PendingEvent pending = readPending(key);
            if (pending == null) {
                pending = PendingEvent.builder()
                        .event(event)
                        .eventCount(0)
                        .build();
            }
            pending.setEvent(event);
            pending.setEventCount(pending.getEventCount() + 1);
            pending.setLastEventAt(System.currentTimeMillis());
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(pending), PENDING_TTL);
        } catch (Exception e) {
            log.warn("Could not enqueue chatbot sync event {}: {}", event, e.getMessage());
        }
    }

    public long pendingCount() {
        try {
            Set<String> keys = redisTemplate.keys(PENDING_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void flushReadyEvents() {
        List<PendingEvent> ready = readyEvents();
        if (ready.isEmpty()) return;

        List<ChatbotSyncEventDTO> events = ready.stream().map(PendingEvent::getEvent).toList();
        int eventCount = ready.stream().mapToInt(PendingEvent::getEventCount).sum();
        String entityTypes = incrementalSyncService.describeEntityTypes(events);

        try {
            vectorSyncService.runEventSync(eventCount, entityTypes,
                    () -> incrementalSyncService.syncEvents(events));
            deletePending(ready);
        } catch (Exception e) {
            log.warn("Debounced chatbot vector sync failed, pending events will retry: {}", e.getMessage());
        }
    }

    private List<PendingEvent> readyEvents() {
        List<PendingEvent> ready = new ArrayList<>();
        try {
            Set<String> keys = redisTemplate.keys(PENDING_PREFIX + "*");
            if (keys == null || keys.isEmpty()) return ready;
            long cutoff = System.currentTimeMillis() - QUIET_WINDOW.toMillis();
            for (String key : keys) {
                PendingEvent pending = readPending(key);
                if (pending != null && pending.getLastEventAt() <= cutoff) {
                    pending.setRedisKey(key);
                    ready.add(pending);
                }
            }
        } catch (Exception e) {
            log.warn("Could not scan pending chatbot sync events: {}", e.getMessage());
        }
        return ready;
    }

    private void deletePending(List<PendingEvent> pendingEvents) {
        for (PendingEvent event : pendingEvents) {
            if (event.getRedisKey() != null) {
                redisTemplate.delete(event.getRedisKey());
            }
        }
    }

    private PendingEvent readPending(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            return raw != null ? objectMapper.readValue(raw, PendingEvent.class) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String keyFor(ChatbotSyncEventDTO event) {
        return PENDING_PREFIX + event.getEntityType().toLowerCase() + ":" + event.getEntityId();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingEvent {
        private String redisKey;
        private ChatbotSyncEventDTO event;
        private long lastEventAt;
        private int eventCount;
    }
}

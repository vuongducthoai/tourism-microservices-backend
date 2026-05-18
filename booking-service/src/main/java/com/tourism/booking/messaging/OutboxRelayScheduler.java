package com.tourism.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads NEW outbox events and publishes them to RabbitMQ.
 *
 * Safety guarantees:
 * - FOR UPDATE SKIP LOCKED  → multiple instances never process the same row
 * - Publisher confirms       → SENT only set after broker ack
 * - Exponential back-off     → SENDING → NEW with next_retry_at delay
 * - DEAD after max_retries   → needs manual / alert intervention
 *
 * Stale lock cleanup runs before each batch to recover rows that were
 * left in SENDING state due to a crashed instance.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayScheduler {

    private static final int BATCH_SIZE    = 100;
    private static final int STALE_MINUTES = 5;

    private final OutboxEventRepository outboxRepo;
    private final RabbitTemplate        rabbitTemplate;
    private final ObjectMapper          objectMapper;

    // ── Main relay loop — every 5 seconds ────────────────────────────────────

    @Scheduled(fixedDelay = 5_000)
    public void relay() {
        // 1. Release stale locks from crashed instances
        int released = outboxRepo.resetStaleLocks(
                LocalDateTime.now().minusMinutes(STALE_MINUTES));
        if (released > 0) {
            log.warn("Released {} stale SENDING outbox locks", released);
        }

        // 2. Claim a batch (FOR UPDATE SKIP LOCKED)
        List<OutboxEvent> batch = claimBatch();
        if (batch.isEmpty()) return;

        log.debug("Processing {} outbox events", batch.size());

        // 3. Publish each event
        for (OutboxEvent event : batch) {
            publishOne(event);
        }
    }

    // ── Claim batch in its own transaction so the lock is held ────────────────

    @Transactional
    public List<OutboxEvent> claimBatch() {
        String instanceId = getInstanceId();
        List<OutboxEvent> allPending = outboxRepo.findAndLockPending(
                LocalDateTime.now(), BATCH_SIZE * 2);

        // Exclude COIN_REFUND rows — handled by CoinRefundRelayScheduler
        List<OutboxEvent> batch = allPending.stream()
                .filter(e -> !RabbitMQConfig.RK_COIN_REFUND.equals(e.getRoutingKey()))
                .limit(BATCH_SIZE)
                .toList();

        // Release coin rows immediately
        allPending.stream()
                .filter(e -> RabbitMQConfig.RK_COIN_REFUND.equals(e.getRoutingKey()))
                .forEach(e -> {
                    e.setStatus(OutboxStatus.NEW);
                    e.setLockedBy(null);
                    e.setLockedAt(null);
                });

        batch.forEach(e -> {
            e.setStatus(OutboxStatus.SENDING);
            e.setLockedBy(instanceId);
            e.setLockedAt(LocalDateTime.now());
        });
        outboxRepo.saveAll(allPending);
        return batch;
    }

    // ── Publish one event — each save is its own transaction ─────────────────

    @Transactional
    public void publishOne(OutboxEvent event) {
        try {
            CorrelationData cd = new CorrelationData(event.getIdempotencyKey());

            // Publisher confirm callback
            cd.getFuture().whenComplete((confirm, ex) -> {
                if (ex != null || confirm == null || !confirm.isAck()) {
                    String reason = ex != null ? ex.getMessage()
                            : (confirm != null ? confirm.getReason() : "null confirm");
                    log.error("RabbitMQ nack for event {}: {}", event.getIdempotencyKey(), reason);
                    scheduleRetry(event, "Broker nack: " + reason);
                } else {
                    event.markSent();
                    outboxRepo.save(event);
                    log.debug("Outbox event {} sent and acked", event.getIdempotencyKey());
                }
            });

            // Send payload as raw JSON bytes to avoid double-serialization
            // (event.getPayload() is already a serialized JSON string)
            Message message = MessageBuilder
                    .withBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding("UTF-8")
                    .build();
            rabbitTemplate.send(
                    event.getExchange(),
                    event.getRoutingKey(),
                    message,
                    cd);

        } catch (Exception e) {
            log.error("Failed to publish outbox event {}: {}", event.getIdempotencyKey(), e.getMessage());
            scheduleRetry(event, e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void scheduleRetry(OutboxEvent event, String error) {
        event.incrementRetries(error);
        outboxRepo.save(event);
        if (event.getStatus() == OutboxStatus.DEAD) {
            log.error("OUTBOX DEAD — event {} has exhausted {} retries. Manual intervention required.",
                    event.getIdempotencyKey(), event.getMaxRetries());
        }
    }

    private String getInstanceId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}

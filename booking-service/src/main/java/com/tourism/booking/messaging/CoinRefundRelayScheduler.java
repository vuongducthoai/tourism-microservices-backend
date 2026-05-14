package com.tourism.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads COIN_REFUND outbox events and calls iam-service via Feign.
 * Uses the same OutboxEvent table as notification relay — distinguished by
 * routing_key = 'booking.coin.refund'.
 *
 * Idempotency: IAM checks coin_transactions table for duplicate operationKey.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoinRefundRelayScheduler {

    private static final int BATCH_SIZE    = 50;
    private static final int STALE_MINUTES = 5;

    private final OutboxEventRepository outboxRepo;
    private final IamFeignClient        iamClient;
    private final ObjectMapper          objectMapper;

    @Scheduled(fixedDelay = 5_000)
    public void relay() {
        List<OutboxEvent> batch = claimBatch();
        if (batch.isEmpty()) return;
        log.debug("Processing {} coin refund outbox events", batch.size());
        for (OutboxEvent event : batch) {
            processOne(event);
        }
    }

    @Transactional
    public List<OutboxEvent> claimBatch() {
        // Reuse findAndLockPending but filter by routing key after claim
        // (PostgreSQL query already uses FOR UPDATE SKIP LOCKED)
        String instanceId = getInstanceId();
        List<OutboxEvent> allPending = outboxRepo.findAndLockPending(
                LocalDateTime.now(), BATCH_SIZE * 10); // over-fetch then filter

        List<OutboxEvent> coinBatch = allPending.stream()
                .filter(e -> RabbitMQConfig.RK_COIN_REFUND.equals(e.getRoutingKey()))
                .limit(BATCH_SIZE)
                .toList();

        coinBatch.forEach(e -> {
            e.setStatus(OutboxStatus.SENDING);
            e.setLockedBy(instanceId);
            e.setLockedAt(LocalDateTime.now());
        });

        // Release non-coin rows back to NEW
        allPending.stream()
                .filter(e -> !RabbitMQConfig.RK_COIN_REFUND.equals(e.getRoutingKey()))
                .forEach(e -> {
                    e.setStatus(OutboxStatus.NEW);
                    e.setLockedBy(null);
                    e.setLockedAt(null);
                });

        outboxRepo.saveAll(allPending);
        return coinBatch;
    }

    @Transactional
    public void processOne(OutboxEvent event) {
        try {
            BookingEventDTO dto = objectMapper.readValue(event.getPayload(), BookingEventDTO.class);

            iamClient.addCoins(
                    dto.getUserId(),
                    dto.getCoinRefundAmount(),
                    dto.getCoinRefundOperationKey());

            event.markSent();
            outboxRepo.save(event);
            log.info("Coin refund of {} coins credited to userId={} for booking {}",
                    dto.getCoinRefundAmount(), dto.getUserId(), dto.getBookingCode());

        } catch (Exception e) {
            log.error("Coin refund failed for event {}: {}", event.getIdempotencyKey(), e.getMessage());
            event.incrementRetries(e.getMessage());
            outboxRepo.save(event);
            if (event.getStatus() == OutboxStatus.DEAD) {
                log.error("COIN REFUND DEAD — event {} needs manual intervention", event.getIdempotencyKey());
            }
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

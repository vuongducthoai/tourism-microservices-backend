package com.tourism.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.repository.BookingRepository;
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
 * Stale locks: reset at start of each cycle (instance crash recovery).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoinRefundRelayScheduler {

    private static final int BATCH_SIZE    = 50;
    private static final int STALE_MINUTES = 5;

    private final OutboxEventRepository outboxRepo;
    private final BookingRepository     bookingRepo;
    private final IamFeignClient        iamClient;
    private final ObjectMapper          objectMapper;

    @Scheduled(fixedDelay = 5_000)
    public void relay() {
        // Release stale locks from crashed instances before claiming new batch
        int released = outboxRepo.resetStaleLocks(
                LocalDateTime.now().minusMinutes(STALE_MINUTES));
        if (released > 0) {
            log.warn("Released {} stale COIN_REFUND outbox locks", released);
        }

        List<OutboxEvent> batch = claimBatch();
        if (batch.isEmpty()) return;
        log.debug("Processing {} coin refund outbox events", batch.size());
        for (OutboxEvent event : batch) {
            processOne(event);
        }
    }

    @Transactional
    public List<OutboxEvent> claimBatch() {
        String instanceId = getInstanceId();
        List<OutboxEvent> allPending = outboxRepo.findAndLockPending(
                LocalDateTime.now(), BATCH_SIZE * 10);

        List<OutboxEvent> coinBatch = allPending.stream()
                .filter(e -> RabbitMQConfig.RK_COIN_REFUND.equals(e.getRoutingKey()))
                .limit(BATCH_SIZE)
                .toList();

        coinBatch.forEach(e -> {
            e.setStatus(OutboxStatus.SENDING);
            e.setLockedBy(instanceId);
            e.setLockedAt(LocalDateTime.now());
        });

        // Release non-coin rows back to NEW immediately
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
        // Parse payload first — if this fails, it's a data bug (not IAM issue)
        BookingEventDTO dto;
        try {
            dto = objectMapper.readValue(event.getPayload(), BookingEventDTO.class);
        } catch (Exception ex) {
            log.error("Cannot parse payload for coin refund event {}: {}",
                    event.getIdempotencyKey(), ex.getMessage());
            event.incrementRetries(ex.getMessage());
            outboxRepo.save(event);
            return;
        }

        try {
            iamClient.addCoins(
                    dto.getUserId(),
                    dto.getCoinRefundAmount(),
                    dto.getCoinRefundOperationKey());

            // [OK] IAM did not throw → mark SENT (covers idempotent re-credit too)
            event.markSent();
            outboxRepo.save(event);

            // Update booking coinRefundStatus → COMPLETED
            bookingRepo.findByBookingCode(dto.getBookingCode()).ifPresent(b -> {
                b.setCoinRefundStatus("COMPLETED");
                bookingRepo.save(b);
            });

            log.info("[COIN_REFUND] COMPLETED: {} coins → userId={}, booking={}",
                    dto.getCoinRefundAmount(), dto.getUserId(), dto.getBookingCode());

        } catch (Exception e) {
            // [FAIL] IAM threw exception → retry with backoff, or DEAD after maxRetries
            log.error("[COIN_REFUND] FAILED event={} booking={}: {}",
                    event.getIdempotencyKey(), dto.getBookingCode(), e.getMessage());
            event.incrementRetries(e.getMessage());
            outboxRepo.save(event);

            if (event.getStatus() == OutboxStatus.DEAD) {
                log.error("[DEAD] COIN_REFUND event={} booking={} userId={} amount={}",
                        event.getIdempotencyKey(), dto.getBookingCode(),
                        dto.getUserId(), dto.getCoinRefundAmount());

                // Update booking coinRefundStatus → FAILED so user/admin can see
                bookingRepo.findByBookingCode(dto.getBookingCode()).ifPresent(b -> {
                    b.setCoinRefundStatus("FAILED");
                    bookingRepo.save(b);
                });
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


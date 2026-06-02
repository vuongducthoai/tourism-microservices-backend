package com.tourism.notification.listener;

import com.tourism.notification.config.RabbitMQConfig;
import com.tourism.notification.dto.BookingEventDTO;
import com.tourism.notification.entity.ProcessedEvent;
import com.tourism.notification.repository.ProcessedEventRepository;
import com.tourism.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes booking events from RabbitMQ.
 *
 * Idempotency: checks processed_events table before delegating.
 * If the same idempotencyKey arrives twice (e.g. after a retry), it is silently skipped.
 *
 * Spring Retry is configured in application.yml:
 *   spring.rabbitmq.listener.simple.retry → 3 attempts with exponential back-off
 *   Messages that still fail are routed to booking.notification.dlq (x-dead-letter).
 *
 * Event routing (eventType field):
 *   BOOKING_CONFIRMED → handleBookingConfirmed
 *   STATUS_UPDATED    → handleStatusUpdated  (CANCELLED coin-path / admin-path)
 *   REFUND_REQUESTED  → handleRefundRequested
 *   REFUND_COMPLETED  → handleStatusUpdated  (admin confirmed bank refund done)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingEventListener {

    private final NotificationService    notificationService;
    private final ProcessedEventRepository processedEventRepo;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_NOTIFICATION)
    @Transactional
    public void onBookingEvent(BookingEventDTO event) {
        String key = resolveProcessedKey(event);
        String rawKey = event.getIdempotencyKey();

        // ── Idempotency check ─────────────────────────────────────────────────
        if (key != null && processedEventRepo.existsByIdempotencyKey(key)) {
            log.info("Skipping duplicate event: key={}, type={}", key, event.getEventType());
            return;
        }

        log.info("Received booking event: type={}, booking={}, key={}",
                event.getEventType(), event.getBookingCode(), key);
        if (rawKey != null && key != null && !rawKey.equals(key)) {
            log.info("Normalized event idempotency key: rawKey={} -> processedKey={}", rawKey, key);
        }

        // ── Dispatch ──────────────────────────────────────────────────────────
        try {
            String eventType = event.getEventType() != null ? event.getEventType() : "";

            switch (eventType) {
                case "BOOKING_CONFIRMED" ->
                        notificationService.handleBookingConfirmed(event);

                case "STATUS_UPDATED", "REFUND_COMPLETED" ->
                        notificationService.handleStatusUpdated(event);

                case "REFUND_REQUESTED" ->
                        notificationService.handleRefundRequested(event);

                case "COIN_WITHDRAWAL" ->
                    notificationService.handleCoinWithdrawal(event);

                case "COIN_WITHDRAWAL_FAILED" ->
                    notificationService.handleCoinWithdrawalFailed(event);

                case "COIN_WITHDRAWAL_MANUAL" ->
                    notificationService.handleCoinWithdrawalManual(event);

                case "CONSULTATION_CREATED" ->
                    notificationService.handleConsultationCreated(event);

                default -> {
                    // Fallback: try to route by bookingStatus for backward compat
                    String status = event.getBookingStatus() != null ? event.getBookingStatus() : "";
                    switch (status) {
                        case "PAID"           -> notificationService.handleBookingConfirmed(event);
                        case "PENDING_REFUND" -> notificationService.handleRefundRequested(event);
                        case "CANCELLED"      -> notificationService.handleStatusUpdated(event);
                        default -> log.warn("Unhandled booking event type={} status={}", eventType, status);
                    }
                }
            }

            // ── Mark processed ─────────────────────────────────────────────────
            if (key != null) {
                try {
                    processedEventRepo.save(ProcessedEvent.builder()
                            .idempotencyKey(key)
                            .eventType(eventType)
                            .build());
                } catch (DataIntegrityViolationException e) {
                    // Race condition on the UNIQUE constraint → already processed by another instance
                    log.info("Concurrent duplicate event ignored: key={}", key);
                }
            }

        } catch (Exception e) {
            log.error("Error processing booking event key={}: {}", key, e.getMessage(), e);
            // Re-throw so Spring Retry / DLQ can handle it
            throw new RuntimeException("Failed to process booking event: " + key, e);
        }
    }

    private String resolveProcessedKey(BookingEventDTO event) {
        String eventType = event.getEventType() != null ? event.getEventType() : "";
        if (eventType.startsWith("COIN_WITHDRAWAL")
                && event.getReferenceCode() != null
                && !event.getReferenceCode().isBlank()) {
            return event.getReferenceCode() + "_" + eventType;
        }
        return event.getIdempotencyKey();
    }
}

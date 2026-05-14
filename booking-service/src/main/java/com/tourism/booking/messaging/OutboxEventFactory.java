package com.tourism.booking.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.event.BookingEventDTO;

import java.time.Instant;

/**
 * Factory for creating OutboxEvent instances from BookingEventDTO.
 * Static helpers — no Spring bean needed.
 */
public final class OutboxEventFactory {

    private OutboxEventFactory() {}

    /**
     * Notification event → routed to notification-service via RabbitMQ.
     * @param eventType BOOKING_CONFIRMED | STATUS_UPDATED | REFUND_REQUESTED | REFUND_COMPLETED
     */
    public static OutboxEvent notification(BookingEventDTO dto,
                                           String eventType,
                                           ObjectMapper mapper) {
        dto.setEventType(eventType);
        String key = buildKey(dto.getBookingCode(), eventType);
        dto.setIdempotencyKey(key);

        return OutboxEvent.builder()
                .idempotencyKey(key)
                .exchange(RabbitMQConfig.EXCHANGE)
                .routingKey(RabbitMQConfig.RK_NOTIFICATION)
                .payload(toJson(dto, mapper))
                .build();
    }

    /**
     * Coin refund event → handled by CoinRefundRelayScheduler via Feign to IAM.
     * NOT published to RabbitMQ.
     */
    public static OutboxEvent coinRefund(BookingEventDTO dto,
                                         ObjectMapper mapper) {
        String key = buildKey(dto.getBookingCode(), "COIN_REFUND");
        dto.setIdempotencyKey(key);
        dto.setCoinRefundOperationKey(key);

        return OutboxEvent.builder()
                .idempotencyKey(key)
                .exchange(RabbitMQConfig.EXCHANGE)  // stored but not used by coin relay
                .routingKey(RabbitMQConfig.RK_COIN_REFUND)
                .payload(toJson(dto, mapper))
                .build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String buildKey(String bookingCode, String eventType) {
        return bookingCode + "_" + eventType + "_" + Instant.now().toEpochMilli();
    }

    private static String toJson(Object obj, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }
}

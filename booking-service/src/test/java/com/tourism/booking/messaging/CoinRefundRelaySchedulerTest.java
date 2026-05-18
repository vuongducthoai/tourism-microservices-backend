package com.tourism.booking.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.repository.BookingRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CoinRefundRelayScheduler.processOne()
 *
 * Covers:
 *  1. Happy path: IAM succeeds → outbox SENT, booking COMPLETED
 *  2. IAM fails: incrementRetries → outbox NEW (not DEAD yet)
 *  3. IAM fails N times → outbox DEAD, booking FAILED
 *  4. Payload parse error → incrementRetries (no IAM call)
 *  5. Booking not found: no exception thrown (findByBookingCode returns empty)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CoinRefundRelayScheduler — processOne()")
class CoinRefundRelaySchedulerTest {

    @Mock OutboxEventRepository outboxRepo;
    @Mock BookingRepository     bookingRepo;
    @Mock IamFeignClient        iamClient;
    @Mock ObjectMapper          objectMapper;

    @InjectMocks CoinRefundRelayScheduler scheduler;

    private OutboxEvent makeEvent(int maxRetries) {
        return OutboxEvent.builder()
                .idempotencyKey("BKtest01_COIN_REFUND_111")
                .exchange(RabbitMQConfig.EXCHANGE)
                .routingKey(RabbitMQConfig.RK_COIN_REFUND)
                .payload("{\"bookingCode\":\"BKtest01\",\"userId\":42,\"coinRefundAmount\":900,\"coinRefundOperationKey\":\"BKtest01_COIN_REFUND_111\"}")
                .maxRetries(maxRetries)
                .maxBackoffSecs(3600L)
                .build();
    }

    private BookingEventDTO makeDto() {
        BookingEventDTO dto = new BookingEventDTO();
        dto.setBookingCode("BKtest01");
        dto.setUserId(42);
        dto.setCoinRefundAmount(new BigDecimal("900"));
        dto.setCoinRefundOperationKey("BKtest01_COIN_REFUND_111");
        return dto;
    }

    @BeforeEach
    void setup() throws Exception {
        lenient().when(objectMapper.readValue(anyString(), eq(BookingEventDTO.class)))
                .thenReturn(makeDto());
        lenient().when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("Happy path — IAM succeeds")
    class HappyPath {

        @Test
        @DisplayName("IAM.addCoins() succeeds → event SENT, booking COMPLETED")
        void iamSucceeds_marksSent_updatesBookingCompleted() {
            OutboxEvent event = makeEvent(20);
            Booking booking = new Booking();
            booking.setBookingCode("BKtest01");
            booking.setCoinRefundStatus("PENDING");

            doNothing().when(iamClient).addCoins(any(), any(), any());
            when(bookingRepo.findByBookingCode("BKtest01")).thenReturn(Optional.of(booking));

            scheduler.processOne(event);

            // Outbox must be SENT
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(event.getSentAt()).isNotNull();
            verify(outboxRepo).save(event);

            // Booking coinRefundStatus must be COMPLETED
            assertThat(booking.getCoinRefundStatus()).isEqualTo("COMPLETED");
            verify(bookingRepo).save(booking);
        }

        @Test
        @DisplayName("Idempotency: IAM succeeds even on re-call (operationKey dedup) → still SENT + COMPLETED")
        void iamIdempotent_noExceptionOnRetry_stillCompletedAndSent() {
            OutboxEvent event = makeEvent(20);
            Booking booking = new Booking();
            booking.setBookingCode("BKtest01");
            booking.setCoinRefundStatus("PENDING");

            // IAM returns 200 on both first call and idempotent re-call
            doNothing().when(iamClient).addCoins(any(), any(), any());
            when(bookingRepo.findByBookingCode("BKtest01")).thenReturn(Optional.of(booking));

            scheduler.processOne(event);
            scheduler.processOne(event); // simulate retry

            // Should be called twice — booking COMPLETED after both
            assertThat(booking.getCoinRefundStatus()).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("IAM failure — retry")
    class IamFailureRetry {

        @Test
        @DisplayName("IAM throws RuntimeException → retries++, status NEW, booking unchanged")
        void iamFails_incrementsRetries_bookingStillPending() {
            OutboxEvent event = makeEvent(20);
            Booking booking = new Booking();
            booking.setBookingCode("BKtest01");
            booking.setCoinRefundStatus("PENDING");

            doThrow(new RuntimeException("Connection refused"))
                    .when(iamClient).addCoins(any(), any(), any());
            // findByBookingCode is only called when event becomes DEAD — use lenient
            lenient().when(bookingRepo.findByBookingCode("BKtest01")).thenReturn(Optional.of(booking));

            scheduler.processOne(event);

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(event.getRetries()).isEqualTo(1);
            // Booking still PENDING — not FAILED (only DEAD transitions to FAILED)
            assertThat(booking.getCoinRefundStatus()).isEqualTo("PENDING");
            verify(bookingRepo, never()).save(any());
        }

        @Test
        @DisplayName("After maxRetries=2 fails → status DEAD, booking coinRefundStatus=FAILED")
        void afterMaxRetries_becomesDead_bookingFailed() {
            OutboxEvent event = makeEvent(2); // DEAD after 2 failures
            Booking booking = new Booking();
            booking.setBookingCode("BKtest01");
            booking.setCoinRefundStatus("PENDING");

            doThrow(new RuntimeException("IAM timeout"))
                    .when(iamClient).addCoins(any(), any(), any());
            when(bookingRepo.findByBookingCode("BKtest01")).thenReturn(Optional.of(booking));

            // First failure: retries=1, status=NEW
            scheduler.processOne(event);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);

            // Reset event to SENDING state for second attempt
            event.setStatus(OutboxStatus.SENDING);
            // Second failure: retries=2 = maxRetries → DEAD
            scheduler.processOne(event);

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
            assertThat(event.getRetries()).isEqualTo(2);
            // Booking must reflect FAILED
            assertThat(booking.getCoinRefundStatus()).isEqualTo("FAILED");
            verify(bookingRepo).save(booking);
        }
    }

    @Nested
    @DisplayName("Payload parse error")
    class PayloadError {

        @Test
        @DisplayName("Bad JSON payload → incrementRetries, IAM never called")
        void badPayload_incrementsRetries_noIamCall() throws Exception {
            OutboxEvent event = makeEvent(20);
            // Override objectMapper to throw
            when(objectMapper.readValue(anyString(), eq(BookingEventDTO.class)))
                    .thenThrow(new RuntimeException("Invalid JSON"));

            scheduler.processOne(event);

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(event.getRetries()).isEqualTo(1);
            verify(iamClient, never()).addCoins(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Booking not found after success")
    class BookingNotFound {

        @Test
        @DisplayName("IAM succeeds but bookingCode not in DB → no NPE, outbox still SENT")
        void bookingNotFound_noException_outboxStillSent() {
            OutboxEvent event = makeEvent(20);
            doNothing().when(iamClient).addCoins(any(), any(), any());
            when(bookingRepo.findByBookingCode("BKtest01")).thenReturn(Optional.empty());

            assertThatNoException().isThrownBy(() -> scheduler.processOne(event));
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        }
    }
}

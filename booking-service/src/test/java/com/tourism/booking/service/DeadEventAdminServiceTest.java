package com.tourism.booking.service;

import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DeadEventAdminService.
 *
 * Covers:
 *  1. listDead: delegates to repository with Pageable
 *  2. countDead: correct split coinRefund / notification / total
 *  3. retryOne: resets DEAD event, throws on not-found or not-DEAD
 *  4. retryAll: resets only DEAD events; with/without routingKey filter
 *     - calling retryAll when nothing DEAD → returns 0, no save
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeadEventAdminService")
class DeadEventAdminServiceTest {

    @Mock OutboxEventRepository outboxRepo;
    @InjectMocks DeadEventAdminService service;

    private OutboxEvent makeDeadCoinEvent(long id) {
        OutboxEvent e = OutboxEvent.builder()
                .idempotencyKey("BK" + id + "_COIN_REFUND")
                .routingKey(RabbitMQConfig.RK_COIN_REFUND)
                .exchange(RabbitMQConfig.EXCHANGE)
                .payload("{}")
                .maxRetries(20)
                .build();
        e.setId(id);
        e.setStatus(OutboxStatus.DEAD);
        e.setRetries(20);
        e.setLockedBy("host-1");
        e.setLockedAt(LocalDateTime.now().minusHours(1));
        return e;
    }

    private OutboxEvent makeDeadNotifEvent(long id) {
        OutboxEvent e = OutboxEvent.builder()
                .idempotencyKey("BK" + id + "_NOTIFICATION")
                .routingKey(RabbitMQConfig.RK_NOTIFICATION)
                .exchange(RabbitMQConfig.EXCHANGE)
                .payload("{}")
                .maxRetries(5)
                .build();
        e.setId(id);
        e.setStatus(OutboxStatus.DEAD);
        e.setRetries(5);
        return e;
    }

    // ── listDead ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listDead")
    class ListDeadTests {

        @Test
        @DisplayName("delegates to findDeadEvents with correct pageable")
        void listDead_delegatesCorrectly() {
            OutboxEvent e1 = makeDeadCoinEvent(1L);
            Page<OutboxEvent> page = new PageImpl<>(List.of(e1), PageRequest.of(0, 20), 1);
            when(outboxRepo.findDeadEvents(any())).thenReturn(page);

            Page<OutboxEvent> result = service.listDead(0, 20);

            assertThat(result.getTotalElements()).isEqualTo(1);
            verify(outboxRepo).findDeadEvents(PageRequest.of(0, 20));
        }
    }

    // ── countDead ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("countDead")
    class CountDeadTests {

        @Test
        @DisplayName("2 coin + 1 notification DEAD → { coinRefund:2, notification:1, total:3 }")
        void countDead_correctSplit() {
            when(outboxRepo.findByStatusAndRoutingKey(OutboxStatus.DEAD, RabbitMQConfig.RK_COIN_REFUND))
                    .thenReturn(List.of(makeDeadCoinEvent(1L), makeDeadCoinEvent(2L)));
            when(outboxRepo.countByStatus(OutboxStatus.DEAD)).thenReturn(3L);

            Map<String, Long> counts = service.countDead();

            assertThat(counts.get("coinRefund")).isEqualTo(2L);
            assertThat(counts.get("notification")).isEqualTo(1L);
            assertThat(counts.get("total")).isEqualTo(3L);
        }

        @Test
        @DisplayName("no DEAD events → { coinRefund:0, notification:0, total:0 }")
        void countDead_allZero() {
            when(outboxRepo.findByStatusAndRoutingKey(any(), any())).thenReturn(List.of());
            when(outboxRepo.countByStatus(OutboxStatus.DEAD)).thenReturn(0L);

            Map<String, Long> counts = service.countDead();
            assertThat(counts.get("total")).isEqualTo(0L);
        }
    }

    // ── retryOne ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retryOne")
    class RetryOneTests {

        @Test
        @DisplayName("DEAD event → reset to NEW, retries=0, locks cleared")
        void retryOne_deadEvent_resetsToNew() {
            OutboxEvent event = makeDeadCoinEvent(10L);
            when(outboxRepo.findById(10L)).thenReturn(Optional.of(event));

            service.retryOne(10L);

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(event.getRetries()).isEqualTo(0);
            assertThat(event.getLockedBy()).isNull();
            assertThat(event.getLockedAt()).isNull();
            assertThat(event.getSentAt()).isNull();
            assertThat(event.getErrorMessage()).isNull();
            assertThat(event.getNextRetryAt()).isNotNull();
            verify(outboxRepo).save(event);
        }

        @Test
        @DisplayName("event not found → IllegalArgumentException")
        void retryOne_notFound_throws() {
            when(outboxRepo.findById(99L)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.retryOne(99L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        @DisplayName("event is SENT (not DEAD) → IllegalStateException, not reset")
        void retryOne_notDead_throws() {
            OutboxEvent event = makeDeadCoinEvent(5L);
            event.setStatus(OutboxStatus.SENT);
            when(outboxRepo.findById(5L)).thenReturn(Optional.of(event));

            assertThatThrownBy(() -> service.retryOne(5L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not DEAD");
            verify(outboxRepo, never()).save(any());
        }
    }

    // ── retryAll ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("retryAll")
    class RetryAllTests {

        @Test
        @DisplayName("routingKey=coin.refund: resets 2 DEAD coin events, returns 2")
        void retryAll_withRoutingKey_resetsCoinEvents() {
            OutboxEvent e1 = makeDeadCoinEvent(1L);
            OutboxEvent e2 = makeDeadCoinEvent(2L);
            when(outboxRepo.findByStatusAndRoutingKey(OutboxStatus.DEAD, RabbitMQConfig.RK_COIN_REFUND))
                    .thenReturn(List.of(e1, e2));

            int count = service.retryAll(RabbitMQConfig.RK_COIN_REFUND);

            assertThat(count).isEqualTo(2);
            assertThat(e1.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(e2.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(e1.getRetries()).isEqualTo(0);
            assertThat(e2.getRetries()).isEqualTo(0);
            verify(outboxRepo).saveAll(List.of(e1, e2));
        }

        @Test
        @DisplayName("routingKey=null: resets all DEAD (coin + notification)")
        void retryAll_noRoutingKey_resetsAll() {
            OutboxEvent coin  = makeDeadCoinEvent(1L);
            OutboxEvent notif = makeDeadNotifEvent(2L);
            when(outboxRepo.findByStatus(OutboxStatus.DEAD))
                    .thenReturn(List.of(coin, notif));

            int count = service.retryAll(null);

            assertThat(count).isEqualTo(2);
            assertThat(coin.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(notif.getStatus()).isEqualTo(OutboxStatus.NEW);
        }

        @Test
        @DisplayName("no DEAD events → returns 0, saveAll called with empty list")
        void retryAll_noDead_returnsZero() {
            when(outboxRepo.findByStatusAndRoutingKey(any(), any())).thenReturn(List.of());

            int count = service.retryAll(RabbitMQConfig.RK_COIN_REFUND);

            assertThat(count).isEqualTo(0);
            // saveAll still called (with empty list — harmless)
            verify(outboxRepo).saveAll(List.of());
        }

        @Test
        @DisplayName("SENT events are NOT touched by retryAll (only DEAD events are returned by query)")
        void retryAll_doesNotTouchSentEvents() {
            // The repository only returns DEAD events — SENT events never come back
            when(outboxRepo.findByStatus(OutboxStatus.DEAD)).thenReturn(List.of());

            int count = service.retryAll(null);

            assertThat(count).isEqualTo(0);
            ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
            verify(outboxRepo).saveAll(captor.capture());
            assertThat(captor.getValue()).isEmpty();
        }

        @Test
        @DisplayName("retryAll twice: second call returns 0 (events now NEW → not DEAD)")
        void retryAll_twice_secondCallReturnsZero() {
            OutboxEvent e1 = makeDeadCoinEvent(1L);
            when(outboxRepo.findByStatusAndRoutingKey(OutboxStatus.DEAD, RabbitMQConfig.RK_COIN_REFUND))
                    .thenReturn(List.of(e1))  // first call
                    .thenReturn(List.of());   // second call — e1 is now NEW

            int first  = service.retryAll(RabbitMQConfig.RK_COIN_REFUND);
            int second = service.retryAll(RabbitMQConfig.RK_COIN_REFUND);

            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(0);
        }
    }
}

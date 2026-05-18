package com.tourism.booking.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for OutboxEvent business logic.
 * Covers:
 *  - incrementRetries: backoff cap (maxBackoffSecs), DEAD transition, lock reset
 *  - markSent: status/sentAt/lock fields
 */
@DisplayName("OutboxEvent — incrementRetries + markSent")
class OutboxEventTest {

    private OutboxEvent makeEvent(int maxRetries, long maxBackoffSecs) {
        return OutboxEvent.builder()
                .idempotencyKey("BKtest_COIN_REFUND_123")
                .exchange("tourism.events")
                .routingKey("booking.coin.refund")
                .payload("{}")
                .maxRetries(maxRetries)
                .maxBackoffSecs(maxBackoffSecs)
                .build();
    }

    @Nested
    @DisplayName("incrementRetries — backoff schedule")
    class BackoffScheduleTests {

        @Test
        @DisplayName("retry 1: 2^1 * 30 = 60s — below cap → nextRetryAt ≈ now + 60s")
        void retry1_below_cap() {
            OutboxEvent event = makeEvent(20, 3600L);
            LocalDateTime before = LocalDateTime.now();

            event.incrementRetries("connection refused");

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(event.getRetries()).isEqualTo(1);
            assertThat(event.getLockedBy()).isNull();
            assertThat(event.getLockedAt()).isNull();
            assertThat(event.getNextRetryAt()).isBetween(
                    before.plusSeconds(58), before.plusSeconds(62));
        }

        @Test
        @DisplayName("retry 7: 2^7 * 30 = 3840s — exceeds cap → capped at 3600s")
        void retry7_capped_at_maxBackoffSecs() {
            OutboxEvent event = makeEvent(20, 3600L);
            LocalDateTime before = LocalDateTime.now();

            // Simulate 6 prior retries by setting retries to 6
            // Then call incrementRetries which will do retries++ making it 7
            event.setRetries(6);
            event.incrementRetries("timeout");

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(event.getRetries()).isEqualTo(7);
            // nextRetryAt should be capped at maxBackoffSecs (3600s), not 3840s
            assertThat(event.getNextRetryAt()).isBetween(
                    before.plusSeconds(3598), before.plusSeconds(3602));
        }

        @Test
        @DisplayName("NO cap bug: old formula 2^20 * 30 = 11_184_640s — should be capped at 3600s")
        void largeRetry_oldBugWouldBe364Days_nowCapped() {
            OutboxEvent event = makeEvent(20, 3600L);
            event.setRetries(19); // next increment → retries=20 → DEAD (for maxRetries=20)
            // Use maxRetries=21 to test the cap at high retry count
            OutboxEvent event2 = makeEvent(25, 3600L);
            event2.setRetries(19);
            LocalDateTime before = LocalDateTime.now();

            event2.incrementRetries("IAM down");

            // retries=20, still < maxRetries=25 → should be NEW with capped backoff
            assertThat(event2.getStatus()).isEqualTo(OutboxStatus.NEW);
            assertThat(event2.getNextRetryAt()).isBetween(
                    before.plusSeconds(3598), before.plusSeconds(3602));
            // Old bug would set nextRetryAt to ~364 days from now — verify it's NOT that
            assertThat(event2.getNextRetryAt()).isBefore(before.plusSeconds(3700));
        }

        @Test
        @DisplayName("DEAD: retries reaches maxRetries → status = DEAD, lock cleared")
        void reachesMaxRetries_becomesDEAD() {
            OutboxEvent event = makeEvent(5, 3600L);
            event.setRetries(4); // next → 5 = maxRetries → DEAD

            event.incrementRetries("permanent failure");

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
            assertThat(event.getRetries()).isEqualTo(5);
            assertThat(event.getLockedBy()).isNull();
            assertThat(event.getLockedAt()).isNull();
            assertThat(event.getErrorMessage()).isEqualTo("permanent failure");
        }

        @Test
        @DisplayName("coin refund event with maxRetries=20: DEAD only after 20 attempts")
        void coinRefundEvent_deadAfter20retries() {
            OutboxEvent event = makeEvent(20, 3600L);

            for (int i = 1; i <= 19; i++) {
                event.incrementRetries("IAM down retry " + i);
                assertThat(event.getStatus())
                        .as("After retry %d should still be NEW", i)
                        .isEqualTo(OutboxStatus.NEW);
            }

            event.incrementRetries("IAM down retry 20");
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
            assertThat(event.getRetries()).isEqualTo(20);
        }
    }

    @Nested
    @DisplayName("markSent")
    class MarkSentTests {

        @Test
        @DisplayName("markSent → status=SENT, sentAt set, lock cleared")
        void markSent_setsFieldsCorrectly() {
            OutboxEvent event = makeEvent(5, 3600L);
            event.setLockedBy("hostname-1234");
            event.setLockedAt(LocalDateTime.now().minusSeconds(2));

            LocalDateTime before = LocalDateTime.now();
            event.markSent();

            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(event.getSentAt()).isBetween(before, before.plusSeconds(1));
            assertThat(event.getLockedBy()).isNull();
            assertThat(event.getLockedAt()).isNull();
        }
    }
}

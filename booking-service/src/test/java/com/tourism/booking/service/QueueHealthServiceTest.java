package com.tourism.booking.service;

import com.tourism.booking.dto.response.QueueHealthResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for QueueHealthService.
 *
 * Covers:
 *  1. determineStatus: HEALTHY / BACKLOG / CONSUMER_DOWN / DLQ_ATTENTION
 *  2. buildMessage: correct Vietnamese message per status
 *  3. checkNotificationQueue: correct mapping from RabbitMQ API response
 *  4. BROKER_DOWN: network failure → fallback response
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueueHealthService")
class QueueHealthServiceTest {

    private QueueHealthService service;
    private RestTemplate mockRestTemplate;

    @BeforeEach
    void setUp() {
        service = new QueueHealthService();
        ReflectionTestUtils.setField(service, "host", "localhost");
        ReflectionTestUtils.setField(service, "port", 15672);
        ReflectionTestUtils.setField(service, "username", "tourism");
        ReflectionTestUtils.setField(service, "password", "tourism123");

        // Replace internal RestTemplate with mock via reflection
        mockRestTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", mockRestTemplate);
    }

    // ── Helper: build a fake RabbitMQ API response ──────────────────────────

    private ResponseEntity<Map> buildQueueResponse(int ready, int unacked, int consumers) {
        Map<String, Object> body = new HashMap<>();
        body.put("messages_ready", ready);
        body.put("messages_unacknowledged", unacked);
        body.put("consumers", consumers);
        return new ResponseEntity<>(body, HttpStatus.OK);
    }

    private ResponseEntity<Map> emptyQueueResponse() {
        return buildQueueResponse(0, 0, 1);
    }

    @SuppressWarnings("unchecked")
    private void stubMainQueue(int ready, int unacked, int consumers) {
        when(mockRestTemplate.exchange(
                argThat((URI uri) -> uri != null && uri.toString().contains("booking.notification.queue")),
                eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(buildQueueResponse(ready, unacked, consumers));
    }

    @SuppressWarnings("unchecked")
    private void stubDlq(int dlqReady) {
        when(mockRestTemplate.exchange(
                argThat((URI uri) -> uri != null && uri.toString().contains("booking.notification.dlq")),
                eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(buildQueueResponse(dlqReady, 0, 0));
    }

    // ── HEALTHY ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status: HEALTHY")
    class HealthyTests {

        @Test
        @DisplayName("returns HEALTHY when queue empty, consumers present, dlq empty")
        void healthy_allClear() {
            stubMainQueue(0, 0, 1);
            stubDlq(0);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getStatus()).isEqualTo("HEALTHY");
            assertThat(resp.getMessage()).contains("bình thường");
            assertThat(resp.getReady()).isEqualTo(0);
            assertThat(resp.getConsumers()).isEqualTo(1);
            assertThat(resp.getDlqReady()).isEqualTo(0);
            assertThat(resp.getCheckedAt()).isNotNull();
        }
    }

    // ── BACKLOG ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status: BACKLOG")
    class BacklogTests {

        @Test
        @DisplayName("returns BACKLOG when messages queued and consumers present")
        void backlog_messagesWaiting() {
            stubMainQueue(5, 0, 1);
            stubDlq(0);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getStatus()).isEqualTo("BACKLOG");
            assertThat(resp.getMessage()).contains("5");
            assertThat(resp.getReady()).isEqualTo(5);
        }
    }

    // ── CONSUMER_DOWN ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status: CONSUMER_DOWN")
    class ConsumerDownTests {

        @Test
        @DisplayName("returns CONSUMER_DOWN when messages queued and no consumers")
        void consumerDown_withMessages() {
            stubMainQueue(3, 0, 0);
            stubDlq(0);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getStatus()).isEqualTo("CONSUMER_DOWN");
            assertThat(resp.getMessage()).contains("tạm dừng");
        }

        @Test
        @DisplayName("returns CONSUMER_DOWN when queue empty but no consumers (notification-service down)")
        void consumerDown_emptyQueueNoConsumers() {
            stubMainQueue(0, 0, 0);
            stubDlq(0);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getStatus()).isEqualTo("CONSUMER_DOWN");
        }
    }

    // ── DLQ_ATTENTION ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status: DLQ_ATTENTION")
    class DlqAttentionTests {

        @Test
        @DisplayName("returns DLQ_ATTENTION when DLQ has messages (takes priority over CONSUMER_DOWN)")
        void dlqAttention_dlqHasMessages() {
            stubMainQueue(0, 0, 0);
            stubDlq(2);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getStatus()).isEqualTo("DLQ_ATTENTION");
            assertThat(resp.getMessage()).contains("thất bại");
            assertThat(resp.getDlqReady()).isEqualTo(2);
        }

        @Test
        @DisplayName("DLQ_ATTENTION wins over CONSUMER_DOWN when both conditions are true")
        void dlqAttention_overridesConsumerDown() {
            stubMainQueue(3, 0, 0);
            stubDlq(1);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getStatus()).isEqualTo("DLQ_ATTENTION");
        }
    }

    // ── BROKER_DOWN ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Status: BROKER_DOWN")
    class BrokerDownTests {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("returns BROKER_DOWN when management API is unreachable")
        void brokerDown_networkFailure() {
            when(mockRestTemplate.exchange(any(URI.class), any(), any(), eq(Map.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getStatus()).isEqualTo("BROKER_DOWN");
            assertThat(resp.getMessage()).contains("Không kiểm tra được");
            assertThat(resp.getCheckedAt()).isNotNull();
        }
    }

    // ── Response structure ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Response structure")
    class ResponseStructureTests {

        @Test
        @DisplayName("queue name is always booking.notification.queue")
        void queueName_isCorrect() {
            stubMainQueue(0, 0, 1);
            stubDlq(0);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getQueue()).isEqualTo("booking.notification.queue");
        }

        @Test
        @DisplayName("unacked value is populated from main queue response")
        void unacked_isPopulated() {
            stubMainQueue(0, 3, 1);
            stubDlq(0);

            QueueHealthResponse resp = service.checkNotificationQueue();

            assertThat(resp.getUnacked()).isEqualTo(3);
        }
    }
}

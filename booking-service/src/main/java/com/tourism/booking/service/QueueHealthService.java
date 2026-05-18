package com.tourism.booking.service;

import com.tourism.booking.dto.response.QueueHealthResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * Queries RabbitMQ Management HTTP API to check notification queue health.
 * Returns a business-friendly Vietnamese status message for the admin UI.
 *
 * Status priority (highest → lowest):
 *   BROKER_DOWN   → cannot reach Management API at all
 *   DLQ_ATTENTION → dlqReady > 0  (messages stuck in dead-letter queue)
 *   CONSUMER_DOWN → consumers == 0 (notification-service is down)
 *   BACKLOG       → ready > 0, consumers >= 1 (queue building up)
 *   HEALTHY       → ready == 0 && dlqReady == 0 && consumers >= 1
 */
@Service
@Slf4j
public class QueueHealthService {

    private static final String MAIN_QUEUE = "booking.notification.queue";
    private static final String DLQ        = "booking.notification.dlq";

    @Value("${rabbitmq.management.host}")
    private String host;

    @Value("${rabbitmq.management.port}")
    private int port;

    @Value("${rabbitmq.management.username}")
    private String username;

    @Value("${rabbitmq.management.password}")
    private String password;

    private final RestTemplate restTemplate = new RestTemplate();

    public QueueHealthResponse checkNotificationQueue() {
        String checkedAt = LocalDateTime.now().toString();
        try {
            HttpHeaders headers = buildAuthHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            int ready     = fetchReady(MAIN_QUEUE, entity);
            int unacked   = fetchUnacked(MAIN_QUEUE, entity);
            int consumers = fetchConsumers(MAIN_QUEUE, entity);
            int dlqReady  = fetchReady(DLQ, entity);

            String status  = determineStatus(ready, consumers, dlqReady);
            String message = buildMessage(status, ready);

            return QueueHealthResponse.builder()
                    .queue(MAIN_QUEUE)
                    .ready(ready)
                    .unacked(unacked)
                    .consumers(consumers)
                    .dlqReady(dlqReady)
                    .status(status)
                    .message(message)
                    .checkedAt(checkedAt)
                    .build();

        } catch (Exception e) {
            log.warn("Queue health check failed: {}", e.getMessage());
            return QueueHealthResponse.builder()
                    .queue(MAIN_QUEUE)
                    .status("BROKER_DOWN")
                    .message("Không kiểm tra được hệ thống hàng đợi thông báo.")
                    .checkedAt(checkedAt)
                    .build();
        }
    }

    // ────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private int fetchReady(String queue, HttpEntity<Void> entity) {
        Map<String, Object> data = fetchQueueData(queue, entity);
        Object val = data.get("messages_ready");
        return val instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private int fetchUnacked(String queue, HttpEntity<Void> entity) {
        Map<String, Object> data = fetchQueueData(queue, entity);
        Object val = data.get("messages_unacknowledged");
        return val instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private int fetchConsumers(String queue, HttpEntity<Void> entity) {
        Map<String, Object> data = fetchQueueData(queue, entity);
        Object val = data.get("consumers");
        return val instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchQueueData(String queue, HttpEntity<Void> entity) {
        // Use URI.create() so RestTemplate does NOT double-encode the %2F vhost separator
        URI uri = URI.create(String.format("http://%s:%d/api/queues/%%2F/%s", host, port, queue));
        ResponseEntity<Map> response = restTemplate.exchange(uri, HttpMethod.GET, entity, Map.class);
        Map<String, Object> body = response.getBody();
        return body != null ? body : Map.of();
    }

    private HttpHeaders buildAuthHeaders() {
        String creds = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(creds.getBytes());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + encoded);
        return headers;
    }

    private String determineStatus(int ready, int consumers, int dlqReady) {
        if (dlqReady > 0)   return "DLQ_ATTENTION";
        if (consumers == 0) return "CONSUMER_DOWN";
        if (ready > 0)      return "BACKLOG";
        return "HEALTHY";
    }

    private String buildMessage(String status, int ready) {
        return switch (status) {
            case "HEALTHY"       -> "Hệ thống gửi thông báo đang hoạt động bình thường.";
            case "BACKLOG"       -> "Đang có " + ready + " thông báo chờ gửi. Hệ thống sẽ tự xử lý.";
            case "CONSUMER_DOWN" -> "Dịch vụ thông báo đang tạm dừng. Email và thông báo sẽ được gửi khi hệ thống hoạt động lại.";
            case "DLQ_ATTENTION" -> "Có thông báo gửi thất bại nhiều lần. Cần kỹ thuật kiểm tra.";
            default              -> "Không kiểm tra được hệ thống hàng đợi thông báo.";
        };
    }
}

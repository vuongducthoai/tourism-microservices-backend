package com.tourism.booking.controller;

import com.tourism.booking.dto.response.QueueHealthResponse;
import com.tourism.booking.dto.response.DeadEventDetailResponse;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.service.DeadEventAdminService;
import com.tourism.booking.service.QueueHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin REST API for DEAD outbox event recovery.
 * No JWT in this version — internal / ops use only.
 * All endpoints return JSON.
 *
 * Base URL: /api/bookings/admin/outbox
 */
@RestController
@RequestMapping("/api/bookings/admin/outbox")
@RequiredArgsConstructor
@Slf4j
public class DeadEventAdminController {

    private final DeadEventAdminService service;
    private final QueueHealthService queueHealthService;

    /**
     * GET /api/bookings/admin/outbox/dead?page=0&size=20
     * List DEAD events with pagination, newest first.
     */
    @GetMapping("/dead")
    public ResponseEntity<Page<OutboxEvent>> listDead(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listDead(page, size));
    }

    /**
     * GET /api/bookings/admin/outbox/dead/{id}
     * Business-friendly detail view enriched from payload + booking DB.
     */
    @GetMapping("/dead/{id}")
    public ResponseEntity<DeadEventDetailResponse> detail(@PathVariable Long id) {
        return ResponseEntity.ok(service.detail(id));
    }

    /**
     * GET /api/bookings/admin/outbox/dead/count
     * Count DEAD events split by type.
     * Response: { "coinRefund": 2, "notification": 1, "total": 3 }
     */
    @GetMapping("/dead/count")
    public ResponseEntity<Map<String, Long>> countDead() {
        return ResponseEntity.ok(service.countDead());
    }

    /**
     * POST /api/bookings/admin/outbox/retry/{id}
     * Reset a single DEAD event back to NEW for the scheduler to retry.
     * Returns 400 if event not found or not DEAD.
     */
    @PostMapping("/retry/{id}")
    public ResponseEntity<Void> retryOne(@PathVariable Long id) {
        service.retryOne(id);
        return ResponseEntity.ok().build();
    }

    /**
     * POST /api/bookings/admin/outbox/retry-all?routingKey=booking.coin.refund
     * Reset all DEAD events (or those matching routingKey) back to NEW.
     * Returns { "retried": N } — N = number of events reset.
     * Passing no routingKey resets ALL DEAD events regardless of type.
     */
    @PostMapping("/retry-all")
    public ResponseEntity<Map<String, Integer>> retryAll(
            @RequestParam(required = false) String routingKey) {
        int count = service.retryAll(routingKey);
        return ResponseEntity.ok(Map.of("retried", count));
    }

    /**
     * GET /api/bookings/admin/outbox/rabbitmq-health
     * Check notification queue health. Returns business-friendly Vietnamese status.
     */
    @GetMapping("/rabbitmq-health")
    public ResponseEntity<QueueHealthResponse> queueHealth() {
        return ResponseEntity.ok(queueHealthService.checkNotificationQueue());
    }
}

package com.tourism.booking.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.AdminContext;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.dto.request.ConsultationCreateRequest;
import com.tourism.booking.dto.request.ConsultationUpdateStatusRequest;
import com.tourism.booking.dto.response.ConsultationResponse;
import com.tourism.booking.entity.ConsultationRequest;
import com.tourism.booking.entity.ConsultationRequest.ConsultationStatus;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.event.ConsultationEventDTO;
import com.tourism.booking.repository.ConsultationRequestRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.service.ConsultationRateLimitService;
import com.tourism.booking.service.ConsultationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConsultationServiceImpl implements ConsultationService {

    private final ConsultationRequestRepository repository;
    private final ConsultationRateLimitService  rateLimitService;
    private final OutboxEventRepository         outboxRepository;
    private final ObjectMapper                  objectMapper;

    @Override
    public ConsultationResponse create(ConsultationCreateRequest req, Integer userId, String clientIp) {
        rateLimitService.check(req.getPhone(), clientIp);

        String code = "CR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        ConsultationRequest entity = ConsultationRequest.builder()
                .requestCode(code)
                .fullName(req.getFullName().trim())
                .phone(req.getPhone().trim())
                .email(req.getEmail().trim())
                .userId(userId)
                .tourId(req.getTourId())
                .tourCode(req.getTourCode())
                .tourName(req.getTourName())
                .consultationInfo(req.getConsultationInfo())
                .status(ConsultationStatus.PENDING)
                .build();

        repository.save(entity);
        log.info("Created consultation {} for phone={} tour={}", code, req.getPhone(), req.getTourCode());

        publishCreatedEvent(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsultationResponse getByCode(String requestCode) {
        ConsultationRequest c = repository.findByRequestCode(requestCode)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy yêu cầu: " + requestCode));
        return toResponse(c);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsultationResponse getById(Integer id) {
        ConsultationRequest c = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy yêu cầu #" + id));
        return toResponse(c);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ConsultationResponse> list(String status, Pageable pageable) {
        Page<ConsultationRequest> page = (status == null || status.isBlank())
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByStatusOrderByCreatedAtDesc(
                        ConsultationStatus.valueOf(status.toUpperCase()), pageable);
        return page.map(this::toResponse);
    }

    @Override
    public ConsultationResponse updateStatus(Integer id, ConsultationUpdateStatusRequest req) {
        ConsultationRequest c = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy yêu cầu #" + id));
        ConsultationStatus newStatus = ConsultationStatus.valueOf(req.getStatus().toUpperCase());
        c.setStatus(newStatus);
        if (req.getAdminNotes() != null) c.setAdminNotes(req.getAdminNotes());
        c.setAdminId(AdminContext.currentUserId());
        c.setAdminEmail(AdminContext.currentEmail());
        if (newStatus == ConsultationStatus.RESOLVED || newStatus == ConsultationStatus.CLOSED) {
            c.setResolvedAt(LocalDateTime.now());
        }
        repository.save(c);
        return toResponse(c);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> stats() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("pending",    repository.countByStatus(ConsultationStatus.PENDING));
        m.put("inProgress", repository.countByStatus(ConsultationStatus.IN_PROGRESS));
        m.put("resolved",   repository.countByStatus(ConsultationStatus.RESOLVED));
        m.put("closed",     repository.countByStatus(ConsultationStatus.CLOSED));
        return m;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void publishCreatedEvent(ConsultationRequest c) {
        try {
            String idemKey = c.getRequestCode() + "_CONSULTATION_CREATED_" + Instant.now().toEpochMilli();

            // Tận dụng BookingEventDTO (đã được consume sẵn ở notification-service)
            // Map: fullName→contactFullName, phone→contactPhone, email→contactEmail,
            //      consultationInfo→cancelReason (tái dùng field text bất kỳ),
            //      requestCode→bookingCode (route key).
            BookingEventDTO dto = BookingEventDTO.builder()
                    .bookingCode(c.getRequestCode())
                    .tourCode(c.getTourCode())
                    .tourName(c.getTourName())
                    .contactFullName(c.getFullName())
                    .contactPhone(c.getPhone())
                    .contactEmail(c.getEmail())
                    .cancelReason(c.getConsultationInfo())
                    .userId(c.getUserId())
                    .eventType("CONSULTATION_CREATED")
                    .idempotencyKey(idemKey)
                    .build();

            OutboxEvent outbox = OutboxEvent.builder()
                    .idempotencyKey(idemKey)
                    .exchange(RabbitMQConfig.EXCHANGE)
                    .routingKey(RabbitMQConfig.RK_NOTIFICATION)
                    .payload(objectMapper.writeValueAsString(dto))
                    .build();
            outboxRepository.save(outbox);
        } catch (JsonProcessingException e) {
            log.error("Failed to publish consultation event for {}: {}", c.getRequestCode(), e.getMessage());
        }
    }

    private ConsultationResponse toResponse(ConsultationRequest c) {
        return ConsultationResponse.builder()
                .consultationId(c.getConsultationId())
                .requestCode(c.getRequestCode())
                .fullName(c.getFullName())
                .phone(c.getPhone())
                .email(c.getEmail())
                .userId(c.getUserId())
                .tourId(c.getTourId())
                .tourCode(c.getTourCode())
                .tourName(c.getTourName())
                .consultationInfo(c.getConsultationInfo())
                .status(c.getStatus().name())
                .adminId(c.getAdminId())
                .adminEmail(c.getAdminEmail())
                .adminNotes(c.getAdminNotes())
                .resolvedAt(c.getResolvedAt())
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}

package com.tourism.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.config.RabbitMQConfig;
import com.tourism.booking.dto.response.DeadEventDetailResponse;
import com.tourism.booking.entity.OutboxEvent;
import com.tourism.booking.entity.OutboxStatus;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.feign.TourCatalogFeignClient;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin service for managing DEAD outbox events.
 * Provides list/count/retry operations so operators can recover
 * from coin refund or notification failures without direct DB access.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadEventAdminService {

    private final OutboxEventRepository outboxRepo;
    private final BookingRepository bookingRepo;
    private final TourCatalogFeignClient tourCatalogClient;
    private final ObjectMapper objectMapper;

    /** Paginated list of DEAD events, newest first */
    public Page<OutboxEvent> listDead(int page, int size) {
        return outboxRepo.findDeadEvents(PageRequest.of(page, size));
    }

    /**
     * Count DEAD events split by type.
     * Response shape: { "coinRefund": N, "notification": M, "total": N+M }
     */
    public Map<String, Long> countDead() {
        long coin  = outboxRepo.findByStatusAndRoutingKey(
                         OutboxStatus.DEAD, RabbitMQConfig.RK_COIN_REFUND).size();
        long total = outboxRepo.countByStatus(OutboxStatus.DEAD);
        return Map.of(
            "coinRefund",   coin,
            "notification", total - coin,
            "total",        total
        );
    }

    @Transactional(readOnly = true)
    public DeadEventDetailResponse detail(Long id) {
        OutboxEvent event = outboxRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + id));

        BookingEventDTO payload = parsePayload(event);
        Map<String, Object> payloadJson = parsePayloadJson(event);
        Optional<Booking> bookingOpt = findBooking(payload);
        Booking booking = bookingOpt.orElse(null);
        DepartureInfoResponse departure = loadDeparture(booking, payload);

        return DeadEventDetailResponse.builder()
                .id(event.getId())
                .taskType(toTaskType(event))
                .status(event.getStatus() != null ? event.getStatus().name() : null)
                .statusLabel(toStatusLabel(event))
                .routingKey(event.getRoutingKey())
                .eventType(firstText(payload.getEventType(), inferEventType(event)))
                .idempotencyKey(event.getIdempotencyKey())
                .retryText(event.getRetries() + " / " + event.getMaxRetries())
                .retries(event.getRetries())
                .maxRetries(event.getMaxRetries())
                .maxBackoffSecs(event.getMaxBackoffSecs())
                .createdAt(event.getCreatedAt())
                .nextRetryAt(event.getNextRetryAt())
                .sentAt(event.getSentAt())
                .lockedBy(event.getLockedBy())
                .lockedAt(event.getLockedAt())
                .latestError(event.getErrorMessage())
                .suggestion(toSuggestion(event, payload, booking))
                .booking(buildBookingInfo(payload, booking, departure))
                .refund(buildRefundInfo(event, payload, booking))
                .rawPayload(event.getPayload())
                .payloadJson(payloadJson)
                .build();
    }

    /** Reset a single DEAD event back to NEW so the scheduler picks it up */
    @Transactional
    public void retryOne(Long id) {
        OutboxEvent event = outboxRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Outbox event not found: " + id));
        if (event.getStatus() != OutboxStatus.DEAD) {
            throw new IllegalStateException(
                "Event " + id + " is not DEAD (current status=" + event.getStatus() + ")");
        }
        resetToNew(event);
        outboxRepo.save(event);
        log.info("[ADMIN] retryOne id={} routingKey={}", id, event.getRoutingKey());
    }

    /**
     * Reset all DEAD events (optionally filtered by routingKey) back to NEW.
     * Only DEAD rows are touched — SENT/NEW/SENDING rows are never modified.
     *
     * @param routingKey null → reset all DEAD; non-null → reset only matching routingKey
     * @return number of events reset
     */
    @Transactional
    public int retryAll(String routingKey) {
        List<OutboxEvent> dead = (routingKey != null && !routingKey.isBlank())
                ? outboxRepo.findByStatusAndRoutingKey(OutboxStatus.DEAD, routingKey)
                : outboxRepo.findByStatus(OutboxStatus.DEAD);

        dead.forEach(this::resetToNew);
        outboxRepo.saveAll(dead);
        log.info("[ADMIN] retryAll count={} routingKey={}", dead.size(), routingKey);
        return dead.size();
    }

    /**
     * Resets all lock/retry/error fields so the scheduler treats the event as fresh.
     * IMPORTANT: lockedBy/lockedAt/sentAt must be cleared or the scheduler will skip it.
     */
    private void resetToNew(OutboxEvent event) {
        event.setStatus(OutboxStatus.NEW);
        event.setRetries(0);
        event.setNextRetryAt(LocalDateTime.now());
        event.setErrorMessage(null);
        event.setLockedBy(null);
        event.setLockedAt(null);
        event.setSentAt(null);
    }

    private BookingEventDTO parsePayload(OutboxEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), BookingEventDTO.class);
        } catch (Exception e) {
            log.warn("Cannot parse outbox payload for event {}: {}", event.getId(), e.getMessage());
            return new BookingEventDTO();
        }
    }

    private Map<String, Object> parsePayloadJson(OutboxEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Optional<Booking> findBooking(BookingEventDTO payload) {
        if (payload.getBookingID() != null) {
            Optional<Booking> byId = bookingRepo.findById(payload.getBookingID());
            if (byId.isPresent()) {
                return byId;
            }
        }
        if (hasText(payload.getBookingCode())) {
            return bookingRepo.findByBookingCode(payload.getBookingCode());
        }
        return Optional.empty();
    }

    private DepartureInfoResponse loadDeparture(Booking booking, BookingEventDTO payload) {
        if (hasText(payload.getTourName()) && hasText(payload.getTourCode()) && hasText(payload.getDepartureDate())) {
            return null;
        }
        if (booking == null || booking.getDepartureId() == null) {
            return null;
        }
        try {
            return tourCatalogClient.getDepartureInfo(booking.getDepartureId());
        } catch (Exception e) {
            log.warn("Cannot enrich dead event with departureId={}: {}", booking.getDepartureId(), e.getMessage());
            return null;
        }
    }

    private DeadEventDetailResponse.BookingInfo buildBookingInfo(
            BookingEventDTO payload,
            Booking booking,
            DepartureInfoResponse departure) {

        return DeadEventDetailResponse.BookingInfo.builder()
                .bookingID(payload.getBookingID() != null ? payload.getBookingID()
                        : booking != null ? booking.getBookingID() : null)
                .bookingCode(firstText(payload.getBookingCode(), booking != null ? booking.getBookingCode() : null))
                .bookingStatus(firstText(payload.getBookingStatus(),
                        booking != null && booking.getBookingStatus() != null ? booking.getBookingStatus().name() : null))
                .userId(payload.getUserId() != null ? payload.getUserId()
                        : booking != null ? booking.getUserId() : null)
                .customerName(firstText(payload.getContactFullName(), booking != null ? booking.getContactFullName() : null))
                .contactEmail(firstText(payload.getContactEmail(), booking != null ? booking.getContactEmail() : null))
                .contactPhone(firstText(payload.getContactPhone(), booking != null ? booking.getContactPhone() : null))
                .contactAddress(firstText(payload.getContactAddress(), booking != null ? booking.getContactAddress() : null))
                .cancelReason(firstText(payload.getCancelReason(), booking != null ? booking.getCancelReason() : null))
                .departureId(booking != null ? booking.getDepartureId() : null)
                .tourName(firstText(payload.getTourName(), departure != null ? departure.getTourName() : null))
                .tourCode(firstText(payload.getTourCode(), departure != null ? departure.getTourCode() : null))
                .departureDate(firstText(payload.getDepartureDate(), departure != null ? departure.getDepartureDate() : null))
                .coinRefundStatus(booking != null ? booking.getCoinRefundStatus() : null)
                .build();
    }

    private DeadEventDetailResponse.RefundInfo buildRefundInfo(
            OutboxEvent event,
            BookingEventDTO payload,
            Booking booking) {

        BigDecimal refundAmount = firstAmount(payload.getRefundAmount(), booking != null ? booking.getRefundAmount() : null);
        BigDecimal coinRefundAmount = firstAmount(payload.getCoinRefundAmount(), inferCoinRefundAmount(event, refundAmount));

        String bank = payload.getRefundBank();
        String accountNumber = payload.getRefundAccountNumber();
        String accountName = payload.getRefundAccountName();
        if ((!hasText(bank) || !hasText(accountNumber) || !hasText(accountName))
                && booking != null && booking.getRefundInformation() != null) {
            bank = firstText(bank, booking.getRefundInformation().getBank());
            accountNumber = firstText(accountNumber, booking.getRefundInformation().getAccountNumber());
            accountName = firstText(accountName, booking.getRefundInformation().getAccountName());
        }

        return DeadEventDetailResponse.RefundInfo.builder()
                .totalPrice(firstAmount(payload.getTotalPrice(), booking != null ? booking.getTotalPrice() : null))
                .paidByCoin(firstAmount(payload.getPaidByCoin(), booking != null ? booking.getPaidByCoin() : null))
                .refundAmount(refundAmount)
                .coinRefundAmount(coinRefundAmount)
                .refundBank(bank)
                .refundAccountNumberMasked(maskAccountNumber(accountNumber))
                .refundAccountName(accountName)
                .build();
    }

    private BigDecimal inferCoinRefundAmount(OutboxEvent event, BigDecimal refundAmount) {
        if (!RabbitMQConfig.RK_COIN_REFUND.equals(event.getRoutingKey()) || refundAmount == null) {
            return null;
        }
        return refundAmount.divide(new BigDecimal("1000"), 0, RoundingMode.DOWN);
    }

    private String toTaskType(OutboxEvent event) {
        if (RabbitMQConfig.RK_COIN_REFUND.equals(event.getRoutingKey())) {
            return "Hoan xu cho khach";
        }
        if (RabbitMQConfig.RK_NOTIFICATION.equals(event.getRoutingKey())) {
            return "Gui email/thong bao";
        }
        return "Tac vu he thong";
    }

    private String toStatusLabel(OutboxEvent event) {
        if (event.getStatus() == null) {
            return "Khong ro";
        }
        return switch (event.getStatus()) {
            case DEAD -> "Can xu ly";
            case NEW -> "Dang cho xu ly";
            case SENDING -> "Dang xu ly";
            case SENT -> "Da xu ly";
        };
    }

    private String toSuggestion(OutboxEvent event, BookingEventDTO payload, Booking booking) {
        if (event.getStatus() != OutboxStatus.DEAD) {
            return "Tac vu chua chet. He thong co the tiep tuc xu ly tu dong.";
        }
        if (RabbitMQConfig.RK_COIN_REFUND.equals(event.getRoutingKey())) {
            if (payload.getUserId() == null && (booking == null || booking.getUserId() == null)) {
                return "Thieu userId de cong xu. Kiem tra booking/user truoc khi thu xu ly lai.";
            }
            return "Kiem tra IAM service va tai khoan khach hang. Sau khi loi duoc xu ly, bam Thu xu ly lai.";
        }
        if (RabbitMQConfig.RK_NOTIFICATION.equals(event.getRoutingKey())) {
            return "Kiem tra RabbitMQ, notification-service va cau hinh mail. Sau khi dich vu on dinh, bam Thu xu ly lai.";
        }
        return "Kiem tra nguyen nhan gan nhat va thu xu ly lai sau khi da sua loi.";
    }

    private String inferEventType(OutboxEvent event) {
        if (RabbitMQConfig.RK_COIN_REFUND.equals(event.getRoutingKey())) {
            return "COIN_REFUND";
        }
        return null;
    }

    private String maskAccountNumber(String accountNumber) {
        if (!hasText(accountNumber)) {
            return null;
        }
        String trimmed = accountNumber.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    private BigDecimal firstAmount(BigDecimal first, BigDecimal fallback) {
        return first != null ? first : fallback;
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first : fallback;
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank() && !"null".equalsIgnoreCase(text.trim());
    }
}

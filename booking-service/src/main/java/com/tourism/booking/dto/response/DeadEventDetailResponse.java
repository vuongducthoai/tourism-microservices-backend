package com.tourism.booking.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadEventDetailResponse {

    private Long id;
    private String taskType;
    private String status;
    private String statusLabel;
    private String routingKey;
    private String eventType;
    private String idempotencyKey;
    private String retryText;
    private int retries;
    private int maxRetries;
    private long maxBackoffSecs;
    private LocalDateTime createdAt;
    private LocalDateTime nextRetryAt;
    private LocalDateTime sentAt;
    private String lockedBy;
    private LocalDateTime lockedAt;
    private String latestError;
    private String suggestion;
    private BookingInfo booking;
    private RefundInfo refund;
    private String rawPayload;
    private Map<String, Object> payloadJson;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BookingInfo {
        private Integer bookingID;
        private String bookingCode;
        private String bookingStatus;
        private Integer userId;
        private String customerName;
        private String contactEmail;
        private String contactPhone;
        private String contactAddress;
        private String cancelReason;
        private Integer departureId;
        private String tourName;
        private String tourCode;
        private String departureDate;
        private String coinRefundStatus;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RefundInfo {
        private BigDecimal totalPrice;
        private BigDecimal paidByCoin;
        private BigDecimal refundAmount;
        private BigDecimal coinRefundAmount;
        private String refundBank;
        private String refundAccountNumberMasked;
        private String refundAccountName;
    }
}

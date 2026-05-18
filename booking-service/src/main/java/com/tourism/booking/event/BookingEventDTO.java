package com.tourism.booking.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Event payload sent to RabbitMQ → notification-service.
 * Mirrors monolith's refund notification data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingEventDTO implements Serializable {

    private Integer    bookingID;
    private String     bookingCode;
    private String     bookingStatus;
    private String     cancelReason;

    private String     tourName;
    private String     tourCode;
    private String     departureDate;

    private String     contactFullName;
    private String     contactEmail;
    private String     contactPhone;
    private String     contactAddress;

    private BigDecimal totalPrice;
    private BigDecimal paidByCoin;
    private BigDecimal refundAmount;

    // Bank refund path
    private String     refundBank;
    private String     refundAccountNumber;
    private String     refundAccountName;

    // Coin refund path
    private BigDecimal coinRefundAmount;

    /** Used by CoinRefundRelayScheduler to pass idempotency key to IAM */
    private String     coinRefundOperationKey;

    private Integer    userId;

    // ── RabbitMQ routing fields ──────────────────────────────────────────────

    /**
     * Discriminator used by BookingEventListener to dispatch to the right handler.
     * Values: BOOKING_CONFIRMED | STATUS_UPDATED | REFUND_REQUESTED | REFUND_COMPLETED
     */
    private String     eventType;

    /**
     * Globally unique key = bookingCode_eventType_epochMs.
     * Consumer checks this before processing to guarantee idempotency.
     */
    private String     idempotencyKey;
}

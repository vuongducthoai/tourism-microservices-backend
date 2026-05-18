package com.tourism.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Event payload published by booking-service via RabbitMQ.
 * Maps to monolith's refund notification data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingEventDTO implements Serializable {

    // Booking info
    private Integer bookingID;
    private String  bookingCode;
    private String  bookingStatus;   // PENDING_REFUND | CANCELLED | PAID | ...
    private String  cancelReason;

    // Tour info (pre-resolved in booking-service)
    private String  tourName;
    private String  tourCode;
    private String  departureDate;

    // Contact / customer info
    private String  contactFullName;
    private String  contactEmail;
    private String  contactPhone;
    private String  contactAddress;

    // Refund amounts
    private BigDecimal totalPrice;
    private BigDecimal paidByCoin;
    private BigDecimal refundAmount;

    // Refund bank account (bank-path)
    private String  refundBank;
    private String  refundAccountNumber;
    private String  refundAccountName;

    // Coin refund info (coin-path)
    private BigDecimal coinRefundAmount;

    /** Idempotency key passed through for coin relay */
    private String  coinRefundOperationKey;

    // User reference
    private Integer userId;

    // ── RabbitMQ routing fields ──────────────────────────────────────────────

    /**
     * Discriminator used by BookingEventListener to dispatch to the right handler.
     * Values: BOOKING_CONFIRMED | STATUS_UPDATED | REFUND_REQUESTED | REFUND_COMPLETED
     */
    private String  eventType;

    /**
     * Globally unique key = bookingCode_eventType_epochMs.
     * Checked before processing to guarantee idempotency (stored in processed_events table).
     */
    private String  idempotencyKey;
}

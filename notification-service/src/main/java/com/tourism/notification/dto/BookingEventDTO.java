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

    // Coin withdrawal info
    private String  referenceCode;
    private BigDecimal coinWithdrawalAmount;
    private BigDecimal withdrawalMoneyAmount;
    private String  withdrawalBank;
    private String  withdrawalAccountNumberMasked;
    private String  withdrawalAccountName;
    private String  withdrawalStatus;
    private String  withdrawalTransferRef;
    private String  withdrawalNote;
    private String  withdrawalErrorSource;

    // User reference
    private Integer userId;

    // Green Fund (GREEN_FUND_THANKS)
    private BigDecimal greenFundCoins;   // số coin user vừa góp
    private Long       greenFundTrees;   // tổng số cây user đã góp (lũy kế)

    // ── RabbitMQ routing fields ──────────────────────────────────────────────

    /**
     * Discriminator used by BookingEventListener to dispatch to the right handler.
    * Values: BOOKING_CONFIRMED | STATUS_UPDATED | REFUND_REQUESTED |
    * REFUND_COMPLETED | COIN_WITHDRAWAL | COIN_WITHDRAWAL_FAILED | COIN_WITHDRAWAL_MANUAL
     */
    private String  eventType;

    /**
     * Globally unique key = bookingCode_eventType_epochMs.
     * Checked before processing to guarantee idempotency (stored in processed_events table).
     */
    private String  idempotencyKey;
}

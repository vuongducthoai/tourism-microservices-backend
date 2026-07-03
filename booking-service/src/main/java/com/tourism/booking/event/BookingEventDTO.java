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

    // Coin withdrawal path
    private String     referenceCode;
    private BigDecimal coinWithdrawalAmount;
    private BigDecimal withdrawalMoneyAmount;
    private String     withdrawalBank;
    private String     withdrawalAccountNumberMasked;
    private String     withdrawalAccountName;
    private String     withdrawalStatus;
    private String     withdrawalTransferRef;
    private String     withdrawalNote;
    private String     withdrawalErrorSource;

    private Integer    userId;

    // ── Green Fund (GREEN_FUND_THANKS) ───────────────────────────────────────
    private BigDecimal greenFundCoins;   // số coin user vừa góp
    private Long       greenFundTrees;   // tổng số cây user đã góp (lũy kế)

    // ── RabbitMQ routing fields ──────────────────────────────────────────────

    /**
     * Discriminator used by BookingEventListener to dispatch to the right handler.
    * Values: BOOKING_CONFIRMED | STATUS_UPDATED | REFUND_REQUESTED |
    * REFUND_COMPLETED | COIN_WITHDRAWAL | COIN_WITHDRAWAL_FAILED | COIN_WITHDRAWAL_MANUAL
     */
    private String     eventType;

    /**
     * Globally unique key = bookingCode_eventType_epochMs.
     * Consumer checks this before processing to guarantee idempotency.
     */
    private String     idempotencyKey;
}

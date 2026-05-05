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

    private Integer    userId;
}

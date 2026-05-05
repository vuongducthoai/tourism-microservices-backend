package com.tourism.booking.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
public class BookingResponse {
    // Booking fields
    private Integer       bookingID;
    private String        bookingCode;
    private LocalDateTime bookingDate;
    private String        contactEmail;
    private String        contactFullName;
    private String        contactPhone;
    private String        contactAddress;
    private String        customerNote;
    private Integer       totalPassengers;
    private BigDecimal    subtotalPrice;
    private BigDecimal    surcharge;
    private BigDecimal    couponDiscount;
    private BigDecimal    paidByCoin;
    private BigDecimal    totalPrice;
    private String        cancelReason;
    private BigDecimal    refundAmount;
    private String        bookingStatus;
    private String        appliedCouponCodes;

    // Tour/Departure info (from tour-catalog-service)
    private Integer       departureID;
    private String        departureDate;
    private Integer       tourID;
    private String        tourCode;
    private String        tourName;
    private String        image;
    private String        duration;

    // Payment info (from payment-service, may be null) — field names match monolith BookingResponseDTO
    private Integer       paymentID;
    private BigDecimal    amount;           // monolith: amount (NOT paymentAmount)
    private LocalDateTime timeLimit;
    private String        bank;             // monolith: bank
    private String        accountNumber;    // monolith: accountNumber
    private String        accountName;      // monolith: accountName

    // Passengers
    private List<BookingPassengerResponse> passengers;

    // Refund info (may be null)
    private String        refundBank;
    private String        refundAccountNumber;
    private String        refundAccountName;
    private String        refundStatus;
}

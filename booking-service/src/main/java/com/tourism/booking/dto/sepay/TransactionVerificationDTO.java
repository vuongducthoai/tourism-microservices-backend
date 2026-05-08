package com.tourism.booking.dto.sepay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionVerificationDTO {
    private String bookingCode;
    private BigDecimal expectedAmount;
    private String expectedAccountNumber;
    private String expectedAccountName;
    private String expectedBank;
    private boolean verified;
    private String transactionReference;
    private String transactionDate;
}

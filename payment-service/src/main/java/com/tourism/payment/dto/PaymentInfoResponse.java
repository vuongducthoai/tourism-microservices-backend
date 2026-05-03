package com.tourism.payment.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class PaymentInfoResponse {
    private Integer       paymentID;
    private BigDecimal    amount;
    private LocalDateTime timeLimit;
    private String        paymentMethod;
    private String        status;
    // Same as monolith Payment entity
    private String        bank;
    private String        accountNumber;
    private String        accountName;
}

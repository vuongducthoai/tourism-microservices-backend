package com.tourism.payment.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PayosCreateRequest {
    private String bookingCode;
    private BigDecimal amount;
    private String description;
    private String returnUrl;
    private String cancelUrl;
}

package com.tourism.payment.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class VnpayCreateRequest {
    private String bookingCode;
    private BigDecimal amount;
    private String orderInfo;
    private String locale;
}

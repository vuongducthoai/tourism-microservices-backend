package com.tourism.booking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CoinWithdrawalRequest {

    @NotNull
    private Integer userId;

    @NotNull
    @DecimalMin(value = "5", inclusive = true)
    private BigDecimal coinAmount;

    @NotBlank
    private String bank;

    @NotBlank
    private String accountNumber;

    @NotBlank
    private String accountName;
}
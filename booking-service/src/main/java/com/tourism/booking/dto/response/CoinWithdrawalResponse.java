package com.tourism.booking.dto.response;

import com.tourism.booking.entity.CoinWithdrawalErrorSource;
import com.tourism.booking.entity.CoinWithdrawalStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class CoinWithdrawalResponse {
    private Long id;
    private String referenceCode;
    private Integer userId;
    private BigDecimal coinAmount;
    private BigDecimal moneyAmount;
    private String bank;
    private String accountNumberMasked;
    private String accountName;
    private CoinWithdrawalStatus status;
    private String transferRef;
    private String operationKey;
    private Integer retryCount;
    private CoinWithdrawalErrorSource errorSource;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
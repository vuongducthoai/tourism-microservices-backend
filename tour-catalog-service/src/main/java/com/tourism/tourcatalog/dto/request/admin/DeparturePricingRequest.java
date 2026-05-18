package com.tourism.tourcatalog.dto.request.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeparturePricingRequest {
    private String passengerType;
    private BigDecimal originalPrice;
    private BigDecimal salePrice;
}

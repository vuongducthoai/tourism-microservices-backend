package com.tourism.tourcatalog.dto.response.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminDeparturePricingItem {
    private Integer pricingID;
    private String passengerType;
    private String ageDescription;
    private BigDecimal originalPrice;
    private BigDecimal salePrice;
}

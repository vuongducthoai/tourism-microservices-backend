package com.tourism.analytics.dto.feign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatbotDepartureInfoResponse {
    private BigDecimal adultPrice;
    private BigDecimal childPrice;
    private BigDecimal toddlerPrice;
    private BigDecimal infantPrice;
    private BigDecimal singleRoomSurcharge;
    private Integer availableSlots;
    private String tourName;
    private String tourCode;
    private String image;
}

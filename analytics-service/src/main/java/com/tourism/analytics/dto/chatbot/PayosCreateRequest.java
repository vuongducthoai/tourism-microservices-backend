package com.tourism.analytics.dto.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PayosCreateRequest {
    private String     bookingCode;
    private BigDecimal amount;
    private String     description;
    private String     returnUrl;
    private String     cancelUrl;
}

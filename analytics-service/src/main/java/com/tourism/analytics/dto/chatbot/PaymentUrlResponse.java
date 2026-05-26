package com.tourism.analytics.dto.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentUrlResponse {
    private String checkoutUrl;    // PayOS checkout link — gửi user nhấn
    private String paymentUrl;     // VNPay (không dùng, giữ compat)
    private String transactionId;  // orderCode PayOS — dùng build paymentWaitingLink
    private String qrCode;         // QR code (optional)
}

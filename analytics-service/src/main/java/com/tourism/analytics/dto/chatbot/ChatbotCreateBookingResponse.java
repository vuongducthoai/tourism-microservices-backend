package com.tourism.analytics.dto.chatbot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatbotCreateBookingResponse {
    private String bookingCode;
    private Long   bookingId;
    private Long   totalPrice;
    private String status;
}

package com.tourism.analytics.feign;

import com.tourism.analytics.dto.chatbot.PaymentUrlResponse;
import com.tourism.analytics.dto.chatbot.PayosCreateRequest;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Feign client gọi sang payment-service cho chatbot booking flow.
 */
@FeignClient(name = "payment-service")
public interface ChatbotPaymentFeignClient {

    @PostMapping("/api/payment/payos/create")
    PaymentUrlResponse createPayosPayment(@RequestBody PayosCreateRequest request);
}

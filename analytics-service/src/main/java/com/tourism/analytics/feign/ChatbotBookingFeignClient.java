package com.tourism.analytics.feign;

import com.tourism.analytics.dto.chatbot.ChatbotBookingDetailResponse;
import com.tourism.analytics.dto.chatbot.ChatbotCreateBookingRequest;
import com.tourism.analytics.dto.chatbot.ChatbotCreateBookingResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * Feign client gọi sang booking-service cho chatbot booking flow.
 */
@FeignClient(name = "booking-service", contextId = "chatbotBookingClient")
public interface ChatbotBookingFeignClient {

    @PostMapping("/api/bookings/create")
    ChatbotCreateBookingResponse createBooking(@RequestBody ChatbotCreateBookingRequest request);

    @GetMapping("/api/bookings/payment/{bookingCode}")
    ChatbotBookingDetailResponse getBookingDetail(@PathVariable("bookingCode") String bookingCode);
}

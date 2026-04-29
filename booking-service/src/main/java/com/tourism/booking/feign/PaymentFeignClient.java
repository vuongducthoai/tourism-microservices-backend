package com.tourism.booking.feign;

import com.tourism.booking.feign.dto.PaymentInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "payment-service")
public interface PaymentFeignClient {

    @GetMapping("/api/payment/by-booking/{bookingId}")
    PaymentInfoResponse getPaymentByBooking(@PathVariable Integer bookingId);
}

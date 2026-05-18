package com.tourism.payment.feign;

import com.tourism.payment.feign.dto.BookingBriefResponse;
import com.tourism.payment.feign.dto.BookingPaymentDetailResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "booking-service")
public interface BookingFeignClient {

    @GetMapping("/api/bookings/{bookingID}")
    BookingBriefResponse getBookingById(@PathVariable Integer bookingID);

    @GetMapping("/api/bookings/payment/{bookingCode}")
    BookingPaymentDetailResponse getBookingByCode(@PathVariable String bookingCode);

    @PostMapping("/api/bookings/{bookingID}/status")
    void updateBookingStatus(@PathVariable Integer bookingID, @RequestParam String status);
}

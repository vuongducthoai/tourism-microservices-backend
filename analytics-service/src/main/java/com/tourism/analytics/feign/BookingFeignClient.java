package com.tourism.analytics.feign;

import com.tourism.analytics.dto.dashboard.feign.BookingStatsResponse;
import com.tourism.analytics.dto.feign.CouponSyncDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Feign client gọi sang booking-service.
 * - Chatbot: lấy coupon active sync lên Pinecone.
 * - Dashboard: lấy booking/revenue stats.
 */
@FeignClient(name = "booking-service")
public interface BookingFeignClient {

    @GetMapping("/api/bookings/coupons/chatbot-sync")
    List<CouponSyncDTO> getCouponsForChatbotSync();

    @GetMapping("/api/admin/bookings/stats")
    BookingStatsResponse getBookingStats(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    );
}

package com.tourism.analytics.feign;

import com.tourism.analytics.dto.feign.CouponSyncDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Feign client gọi sang booking-service để lấy danh sách coupon active sync lên Pinecone.
 * Eureka service name: "booking-service"
 */
@FeignClient(name = "booking-service")
public interface BookingFeignClient {

    /**
     * GET /api/bookings/coupons/chatbot-sync
     * Lấy tất cả coupon đang active để sync lên Vector DB.
     */
    @GetMapping("/api/bookings/coupons/chatbot-sync")
    List<CouponSyncDTO> getCouponsForChatbotSync();
}

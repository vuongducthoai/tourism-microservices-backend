package com.tourism.analytics.feign;

import com.tourism.analytics.dto.feign.LocationSyncDTO;
import com.tourism.analytics.dto.feign.ReviewSyncDTO;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * Feign client gọi sang tour-catalog-service để lấy dữ liệu sync lên Pinecone.
 * Eureka service name: "tour-catalog-service"
 */
@FeignClient(name = "tour-catalog-service")
public interface TourCatalogFeignClient {

    /**
     * GET /api/tours/chatbot-sync
     * Lấy toàn bộ tour active với departures + pricing để sync lên Vector DB.
     */
    @GetMapping("/api/tours/chatbot-sync")
    List<TourSyncDTO> getAllToursForChatbotSync();

    /**
     * GET /api/locations/chatbot-sync
     * Lấy toàn bộ điểm đến active với đầy đủ thông tin (region, airport).
     */
    @GetMapping("/api/locations/chatbot-sync")
    List<LocationSyncDTO> getLocationsForChatbotSync();

    /**
     * GET /api/reviews/chatbot-sync
     * Lấy toàn bộ review visible để sync lên Vector DB.
     */
    @GetMapping("/api/reviews/chatbot-sync")
    List<ReviewSyncDTO> getAllVisibleReviews();
}

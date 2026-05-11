package com.tourism.analytics.feign;

import com.tourism.analytics.dto.dashboard.feign.TourStatsResponse;
import com.tourism.analytics.dto.feign.LocationSyncDTO;
import com.tourism.analytics.dto.feign.ReviewSyncDTO;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Feign client gọi sang tour-catalog-service.
 * - Chatbot: sync dữ liệu tour/location/review lên Pinecone.
 * - Dashboard: lấy tour stats.
 */
@FeignClient(name = "tour-catalog-service")
public interface TourCatalogFeignClient {

    @GetMapping("/api/tours/chatbot-sync")
    List<TourSyncDTO> getAllToursForChatbotSync();

    @GetMapping("/api/locations/chatbot-sync")
    List<LocationSyncDTO> getLocationsForChatbotSync();

    @GetMapping("/api/reviews/chatbot-sync")
    List<ReviewSyncDTO> getAllVisibleReviews();

    @GetMapping("/api/admin/tours/stats")
    TourStatsResponse getTourStats(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    );
}

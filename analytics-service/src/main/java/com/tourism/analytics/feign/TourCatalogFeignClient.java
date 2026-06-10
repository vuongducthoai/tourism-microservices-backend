package com.tourism.analytics.feign;

import com.tourism.analytics.dto.dashboard.feign.TourStatsResponse;
import com.tourism.analytics.dto.feign.ChatbotDepartureInfoResponse;
import com.tourism.analytics.dto.feign.LocationSyncDTO;
import com.tourism.analytics.dto.feign.ReviewSyncDTO;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Feign client gọi sang tour-catalog-service.
 * - Chatbot: sync dữ liệu tour/location/review lên Pinecone.
 * - Dashboard: lấy tour stats.
 * - Booking flow: lấy giá chi tiết theo loại hành khách.
 */
@FeignClient(name = "tour-catalog-service")
public interface TourCatalogFeignClient {

    @GetMapping("/api/tours/chatbot-sync")
    List<TourSyncDTO> getAllToursForChatbotSync();

    @GetMapping("/api/tours/chatbot-sync/{tourId}")
    TourSyncDTO getTourForChatbotSync(@PathVariable("tourId") Integer tourId);

    @GetMapping("/api/locations/chatbot-sync")
    List<LocationSyncDTO> getLocationsForChatbotSync();

    @GetMapping("/api/locations/chatbot-sync/{locationId}")
    LocationSyncDTO getLocationForChatbotSync(@PathVariable("locationId") Integer locationId);

    @GetMapping("/api/reviews/chatbot-sync")
    List<ReviewSyncDTO> getAllVisibleReviews();

    @GetMapping("/api/reviews/chatbot-sync/{reviewId}")
    ReviewSyncDTO getReviewForChatbotSync(@PathVariable("reviewId") Integer reviewId);

    @GetMapping("/api/admin/tours/stats")
    TourStatsResponse getTourStats(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to
    );

    /**
     * Lấy giá chi tiết (adult/child/toddler/infant) theo departureId.
     * Endpoint đã có trong tour-catalog-service/DepartureController.getOrderInfo()
     */
    @GetMapping("/api/departures/order-info")
    ChatbotDepartureInfoResponse getDepartureOrderInfo(@RequestParam("departureId") Integer departureId);
}

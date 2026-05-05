package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.TourChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.dto.response.TourSpecialResponse;
import com.tourism.tourcatalog.service.TourService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * TourController — public endpoints, không cần auth.
 *
 * Endpoints:
 *  GET  /api/tours/display          — tất cả tour active cho trang danh sách / homepage
 *  GET  /api/tours/deepest-discount — top 10 tour giảm giá sâu nhất cho homepage
 *  GET  /api/tours/search           — tìm kiếm tour với filter tùy chọn (trang /tours)
 *  GET  /api/tours/chatbot-sync     — dữ liệu đầy đủ để analytics-service sync chatbot
 */
@RestController
@RequestMapping("/api/tours")
@RequiredArgsConstructor
public class TourController {

    private final TourService tourService;

    @GetMapping("/display")
    public ResponseEntity<List<TourDisplayResponse>> getToursForDisplay() {
        return ResponseEntity.ok(tourService.getAllToursForDisplay());
    }

    @GetMapping("/deepest-discount")
    public ResponseEntity<List<TourSpecialResponse>> getDeepestDiscountTours() {
        return ResponseEntity.ok(tourService.getTop10DeepestDiscountTours());
    }

    @GetMapping("/search")
    public ResponseEntity<List<TourSearchResponse>> searchTours(
            @ModelAttribute SearchToursRequest request) {
        return ResponseEntity.ok(tourService.searchTours(request));
    }

    /**
     * GET /api/tours/chatbot-sync
     *
     * Trả về toàn bộ tour active với thông tin đầy đủ (departures + pricings + rating)
     * để analytics-service đồng bộ lên Pinecone Vector DB.
     * Endpoint nội bộ — gọi từ analytics-service qua Feign.
     */
    @GetMapping("/chatbot-sync")
    public ResponseEntity<List<TourChatbotSyncResponse>> getChatbotSyncData() {
        return ResponseEntity.ok(tourService.getAllToursForChatbotSync());
    }
}


package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.RelatedTourResponse;
import com.tourism.tourcatalog.dto.response.TourChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.TourDetailResponse;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.dto.response.TourSpecialResponse;
import com.tourism.tourcatalog.service.TourService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tours")
@RequiredArgsConstructor
@Tag(name = "Tours", description = "Danh sách và tìm kiếm tour du lịch")
public class TourController {

    private final TourService tourService;

    @Operation(summary = "Lấy danh sách tour để hiển thị", description = "Tất cả tour active — dùng cho trang danh sách và homepage")
    @ApiResponse(responseCode = "200", description = "Danh sách tour")
    @GetMapping("/display")
    public ResponseEntity<List<TourDisplayResponse>> getToursForDisplay() {
        return ResponseEntity.ok(tourService.getAllToursForDisplay());
    }

    @Operation(summary = "Top 10 tour giảm giá sâu nhất", description = "Dùng cho khu section ưu đãi trên homepage")
    @ApiResponse(responseCode = "200", description = "Danh sách top 10")
    @GetMapping("/deepest-discount")
    public ResponseEntity<List<TourSpecialResponse>> getDeepestDiscountTours() {
        return ResponseEntity.ok(tourService.getTop10DeepestDiscountTours());
    }

    @Operation(summary = "Tìm kiếm tour", description = "Lọc theo điểm đi, điểm đến, ngày khởi hành, số người, khoảng giá")
    @ApiResponse(responseCode = "200", description = "Kết quả tìm kiếm")
    @GetMapping("/search")
    public ResponseEntity<List<TourSearchResponse>> searchTours(
            @ModelAttribute SearchToursRequest request) {
        return ResponseEntity.ok(tourService.searchTours(request));
    }

    @Operation(summary = "[Internal] Chatbot sync data", description = "Endpoint nội bộ — analytics-service gọi qua Feign để lấy tóm tắt tour cho Pinecone")
    @ApiResponse(responseCode = "200", description = "Danh sách tour đầy đủ")
    @GetMapping("/chatbot-sync")
    public ResponseEntity<List<TourChatbotSyncResponse>> getChatbotSyncData() {
        return ResponseEntity.ok(tourService.getAllToursForChatbotSync());
    }

    @Operation(summary = "Lấy tour liên quan", description = "3 tour cùng điểm đến")
    @GetMapping("/related/{tourCode}")
    public ResponseEntity<List<RelatedTourResponse>> getRelatedTours(@PathVariable String tourCode) {
        return ResponseEntity.ok(tourService.getRelatedTours(tourCode));
    }

    @Operation(summary = "Chi tiết tour", description = "Trang chi tiết tour theo tourCode")
    @GetMapping("/{tourCode}")
    public ResponseEntity<TourDetailResponse> getTourDetail(@PathVariable String tourCode) {
        return ResponseEntity.ok(tourService.getTourDetailByCode(tourCode));
    }
}


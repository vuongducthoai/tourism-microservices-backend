package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
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
 *  GET  /api/tours/display         — tất cả tour active cho trang danh sách / homepage
 *  GET  /api/tours/deepest-discount — top 10 tour giảm giá sâu nhất cho homepage
 *  GET  /api/tours/search          — tìm kiếm tour với filter tùy chọn (trang /tours)
 *
 * Frontend (Banner.jsx) gọi /tours/display qua useFeaturedTours hook.
 * Frontend (FilterAndSearchInput.jsx) gọi /tours/search qua query params.
 */
@RestController
@RequestMapping("/api/tours")
@RequiredArgsConstructor
public class TourController {

    private final TourService tourService;

    /**
     * Lấy tất cả tour active để hiển thị danh sách.
     * Response shape: TourDisplayResponse[]
     *   tourID, tourCode, tourName, endPointName, transportation, duration,
     *   departureDate[], money (Long), image
     */
    @GetMapping("/display")
    public ResponseEntity<List<TourDisplayResponse>> getToursForDisplay() {
        return ResponseEntity.ok(tourService.getAllToursForDisplay());
    }

    /**
     * Top 10 tour giảm giá sâu nhất, dùng cho section SpecialTours trang chủ.
     * Kết quả đã sort giảm dần theo discountPercentage.
     * Response shape: TourSpecialResponse[]
     *   departureID, tourID, tourName, tourCode, startLocationName, duration,
     *   departureDate (String), availableSlots, salePrice, originalPrice,
     *   discountPercentage, image
     */
    @GetMapping("/deepest-discount")
    public ResponseEntity<List<TourSpecialResponse>> getDeepestDiscountTours() {
        return ResponseEntity.ok(tourService.getTop10DeepestDiscountTours());
    }

    /**
     * Tìm kiếm tour với filter động — frontend gửi qua URL query params.
     *
     * Query params (tất cả optional):
     *   searchNameTour  — tên tour / điểm đến (fuzzy LIKE)
     *   budget          — chuỗi ngân sách (xử lý ở frontend, chuyển thành startPrice/endPrice)
     *   startPrice      — giá tối thiểu (BigDecimal)
     *   endPrice        — giá tối đa (BigDecimal)
     *   startLocationID — ID điểm khởi hành (Integer, null = tất cả)
     *   endLocationID   — ID điểm đến (Integer, null = tất cả)
     *   transportation  — phương tiện ("Máy bay", "Xe", ...)
     *   rating          — đánh giá tối thiểu 1-5 (0 hoặc null = tất cả)
     *
     * Response: TourDisplayResponse[] (cùng shape với /display)
     */
    @GetMapping("/search")
    public ResponseEntity<List<TourDisplayResponse>> searchTours(
            @ModelAttribute SearchToursRequest request) {
        List<TourDisplayResponse> result = tourService.searchTours(request);
        return ResponseEntity.ok(result);
    }
}

package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.service.FavoriteTourService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/favorite-tours")
@RequiredArgsConstructor
@Tag(name = "Favorite Tours", description = "Quản lý tour yêu thích của người dùng")
public class FavoriteTourController {

    private final FavoriteTourService favoriteTourService;

    @Operation(summary = "Lấy danh sách tour yêu thích", description = "Trả về tất cả tour được user yêu thích (isFavorite=true)")
    @ApiResponse(responseCode = "200", description = "Danh sách tour yêu thích")
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TourSearchResponse>> getFavoriteTours(@PathVariable Integer userId) {
        return ResponseEntity.ok(favoriteTourService.getFavoriteTours(userId));
    }

    @Operation(summary = "Thêm tour vào yêu thích", description = "Idempotent — không lỗi nếu đã tồn tại. Params: userId, tourId")
    @ApiResponse(responseCode = "200", description = "Đã thêm")
    @PostMapping("/add")
    public ResponseEntity<String> addFavorite(@RequestParam Integer userId,
                                              @RequestParam Integer tourId) {
        favoriteTourService.addFavoriteTour(userId, tourId);
        return ResponseEntity.ok("Added to favorites");
    }

    @Operation(summary = "Xóa tour khỏi yêu thích", description = "Xóa tour ra khỏi danh sách yêu thích của user")
    @ApiResponse(responseCode = "200", description = "Đã xóa")
    @DeleteMapping("/remove")
    public ResponseEntity<String> removeFavorite(@RequestParam Integer userId,
                                                 @RequestParam Integer tourId) {
        favoriteTourService.removeFavoriteTour(userId, tourId);
        return ResponseEntity.ok("Removed from favorites");
    }
}

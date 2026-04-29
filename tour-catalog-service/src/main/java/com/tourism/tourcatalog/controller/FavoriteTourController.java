package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.service.FavoriteTourService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/favorite-tours")
@RequiredArgsConstructor
public class FavoriteTourController {

    private final FavoriteTourService favoriteTourService;

    /**
     * GET /api/favorite-tours/user/{userId}
     * Returns list of tours favorited by the user (TourSearchResponse with isFavorite=true).
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TourSearchResponse>> getFavoriteTours(@PathVariable Integer userId) {
        return ResponseEntity.ok(favoriteTourService.getFavoriteTours(userId));
    }

    /**
     * POST /api/favorite-tours/add?userId=&tourId=
     * Adds a tour to user's favorites. Idempotent — no error if already exists.
     */
    @PostMapping("/add")
    public ResponseEntity<String> addFavorite(@RequestParam Integer userId,
                                              @RequestParam Integer tourId) {
        favoriteTourService.addFavoriteTour(userId, tourId);
        return ResponseEntity.ok("Added to favorites");
    }

    /**
     * DELETE /api/favorite-tours/remove?userId=&tourId=
     * Removes a tour from user's favorites.
     */
    @DeleteMapping("/remove")
    public ResponseEntity<String> removeFavorite(@RequestParam Integer userId,
                                                 @RequestParam Integer tourId) {
        favoriteTourService.removeFavoriteTour(userId, tourId);
        return ResponseEntity.ok("Removed from favorites");
    }
}

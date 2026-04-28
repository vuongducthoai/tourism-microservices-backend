package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.RegionRequest;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.service.LocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * LocationController — public endpoints, không cần auth.
 *
 * Endpoints:
 *  GET  /api/locations/start-location           — điểm khởi hành (cho dropdown Banner + Filter)
 *  GET  /api/locations/end-location             — điểm đến (cho dropdown Banner + Filter)
 *  POST /api/locations/destinations-by-region   — điểm đến nổi bật theo vùng miền (homepage)
 *
 * Frontend hooks:
 *   useLocations            -> gọi cả 2 GET endpoints song song
 *   useFavoriteDestinations -> gọi POST destinations-by-region
 */
@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    /**
     * Danh sách điểm khởi hành có tour active.
     * Response shape: LocationResponse[]
     *   locationID, name, imageUrl (← location.image), description
     */
    @GetMapping("/start-location")
    public ResponseEntity<List<LocationResponse>> getStartLocations() {
        return ResponseEntity.ok(locationService.getStartLocations());
    }

    /**
     * Danh sách điểm đến có tour active.
     * Dùng cho LocationDropdown trong Banner và FilterAndSearchInput.
     */
    @GetMapping("/end-location")
    public ResponseEntity<List<LocationResponse>> getEndLocations() {
        return ResponseEntity.ok(locationService.getEndLocations());
    }

    /**
     * Điểm đến nổi bật theo vùng miền (NORTH / CENTRAL / SOUTH).
     *
     * Request body: { "region": "NORTH" }
     * Response shape: DestinationResponse[]
     *   locationID, endPoint (← name), listImage (← image), region (← region.name())
     *
     * 400 Bad Request nếu region value không hợp lệ.
     */
    @PostMapping("/destinations-by-region")
    public ResponseEntity<List<DestinationResponse>> getDestinationsByRegion(
            @Valid @RequestBody RegionRequest request) {
        return ResponseEntity.ok(locationService.getDestinationsByRegion(request));
    }
}

package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.LocationChatbotSyncResponse;
import com.tourism.tourcatalog.dto.request.RegionRequest;
import com.tourism.tourcatalog.dto.response.DestinationResponse;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
@Tag(name = "Locations", description = "Điểm khởi hành và điểm đến du lịch")
public class LocationController {

    private final LocationService locationService;

    @Operation(summary = "Danh sách điểm khởi hành", description = "Trả về các điểm khởi hành có tour active — dùng cho dropdown Banner và Filter")
    @ApiResponse(responseCode = "200", description = "Danh sách điểm khởi hành")
    @GetMapping("/start-location")
    public ResponseEntity<List<LocationResponse>> getStartLocations() {
        return ResponseEntity.ok(locationService.getStartLocations());
    }

    @Operation(summary = "Danh sách điểm đến", description = "Trả về các điểm đến có tour active — dùng cho LocationDropdown trong Banner và FilterAndSearchInput")
    @ApiResponse(responseCode = "200", description = "Danh sách điểm đến")
    @GetMapping("/end-location")
    public ResponseEntity<List<LocationResponse>> getEndLocations() {
        return ResponseEntity.ok(locationService.getEndLocations());
    }

    @Operation(summary = "Điểm đến nổi bật theo vùng miền", description = "Body: { \"region\": \"NORTH\" | \"CENTRAL\" | \"SOUTH\" }. Dùng cho section \"Khám phá Việt Nam\" trên homepage")
    @ApiResponse(responseCode = "200", description = "Điểm đến nổi bật")
    @PostMapping("/destinations-by-region")
    public ResponseEntity<List<DestinationResponse>> getDestinationsByRegion(
            @Valid @RequestBody RegionRequest request) {
        return ResponseEntity.ok(locationService.getDestinationsByRegion(request));
    }

    @Operation(summary = "[Internal] Chatbot sync locations", description = "Endpoint nội bộ — analytics-service gọi qua Feign để lấy địa điểm cho Pinecone")
    @ApiResponse(responseCode = "200", description = "Danh sách location đầy đủ")
    @GetMapping("/chatbot-sync")
    public ResponseEntity<List<LocationChatbotSyncResponse>> getChatbotSyncLocations() {
        return ResponseEntity.ok(locationService.getAllLocationsForChatbotSync());
    }
}

package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.admin.CreateTourRequest;
import com.tourism.tourcatalog.dto.request.admin.ItineraryDayRequest;
import com.tourism.tourcatalog.dto.request.admin.TourGeneralInfoRequest;
import com.tourism.tourcatalog.dto.response.LocationResponse;
import com.tourism.tourcatalog.dto.response.admin.*;
import com.tourism.tourcatalog.service.AdminTourService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/tours")
@RequiredArgsConstructor
public class AdminTourController {

    private final AdminTourService adminTourService;

    // GET /api/admin/tours?page=0&size=10&sortBy=tourID&sortDirection=DESC
    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllTours(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "tourID") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDirection) {
        PagedAdminResponse<AdminTourListItem> result = adminTourService.getAllTours(page, size, sortBy, sortDirection);
        return ok(result);
    }

    // GET /api/admin/tours/search?keyword=xxx&page=0&size=10
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchTours(
            @RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PagedAdminResponse<AdminTourListItem> result = adminTourService.searchTours(keyword, page, size);
        return ok(result);
    }

    // GET /api/admin/tours/check-code?tourCode=xxx
    @GetMapping("/check-code")
    public ResponseEntity<Map<String, Object>> checkTourCode(@RequestParam String tourCode) {
        boolean exists = adminTourService.checkTourCodeExists(tourCode);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("exists", exists);
        return ResponseEntity.ok(body);
    }

    // GET /api/admin/tours/locations
    @GetMapping("/locations")
    public ResponseEntity<Map<String, Object>> getLocations() {
        List<LocationResponse> locations = adminTourService.getAllLocations();
        return ok(locations);
    }

    // GET /api/admin/tours/{tourId}
    @GetMapping("/{tourId}")
    public ResponseEntity<Map<String, Object>> getTourById(@PathVariable Integer tourId) {
        AdminTourDetailResponse tour = adminTourService.getTourById(tourId);
        return ok(tour);
    }

    // POST /api/admin/tours
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTour(@RequestBody CreateTourRequest request) {
        AdminTourDetailResponse tour = adminTourService.createTour(request);
        return ok(tour);
    }

    // PUT /api/admin/tours/{tourId}/general-info
    @PutMapping("/{tourId}/general-info")
    public ResponseEntity<Map<String, Object>> updateGeneralInfo(
            @PathVariable Integer tourId,
            @RequestBody TourGeneralInfoRequest request) {
        AdminTourDetailResponse tour = adminTourService.updateGeneralInfo(tourId, request);
        return ok(tour);
    }

    // PUT /api/admin/tours/{tourId}/itinerary
    @PutMapping("/{tourId}/itinerary")
    public ResponseEntity<Map<String, Object>> updateItinerary(
            @PathVariable Integer tourId,
            @RequestBody List<ItineraryDayRequest> days) {
        adminTourService.updateItinerary(tourId, days);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Itinerary updated successfully");
        return ResponseEntity.ok(body);
    }

    // POST /api/admin/tours/{tourId}/upload-image
    @PostMapping("/{tourId}/upload-image")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Integer tourId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean isMain) {
        AdminImageResponse image = adminTourService.uploadImage(tourId, file, isMain);
        return ok(image);
    }

    // POST /api/admin/tours/{tourId}/upload-media
    @PostMapping("/{tourId}/upload-media")
    public ResponseEntity<Map<String, Object>> uploadMedia(
            @PathVariable Integer tourId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "video") String mediaType) {
        AdminMediaResponse media = adminTourService.uploadMedia(tourId, file, mediaType);
        return ok(media);
    }

    // DELETE /api/admin/tours/{tourId}
    @DeleteMapping("/{tourId}")
    public ResponseEntity<Map<String, Object>> deleteTour(@PathVariable Integer tourId) {
        adminTourService.deleteTour(tourId);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Tour deleted successfully");
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}

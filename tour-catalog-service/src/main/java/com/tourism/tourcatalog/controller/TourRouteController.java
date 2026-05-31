package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.TourStopRequest;
import com.tourism.tourcatalog.service.ItineraryRouteService;
import com.tourism.tourcatalog.service.TourRouteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API quản lý lộ trình bản đồ (Leaflet) — pin các điểm dừng theo ngày.
 */
@RestController
@RequiredArgsConstructor
public class TourRouteController {

    private final TourRouteService tourRouteService;
    private final ItineraryRouteService itineraryRouteService;

    // ── Public ────────────────────────────────────────────────────────────────
    @GetMapping("/api/tours/{tourCode}/route")
    public ResponseEntity<?> getRoute(@PathVariable String tourCode) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", tourRouteService.getRoute(tourCode)));
    }

    /**
     * Composite endpoint: itinerary + map stops + globalIndex khớp.
     * Dùng cho TourDetail page (1 fetch render được cả 2 component).
     */
    @GetMapping("/api/tours/{tourCode}/itinerary-with-route")
    public ResponseEntity<?> getItineraryWithRoute(@PathVariable String tourCode) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", itineraryRouteService.getCombined(tourCode)));
    }

    // ── Admin ─────────────────────────────────────────────────────────────────
    @GetMapping("/api/admin/tours/{tourId}/stops")
    public ResponseEntity<?> getStops(@PathVariable Integer tourId) {
        return ResponseEntity.ok(Map.of("success", true,
                "data", tourRouteService.getStopsByTourId(tourId)));
    }

    @PutMapping("/api/admin/tours/{tourId}/stops")
    public ResponseEntity<?> upsertStops(@PathVariable Integer tourId,
                                         @Valid @RequestBody List<TourStopRequest> body) {
        return ResponseEntity.ok(Map.of("success", true,
                "message", "Đã cập nhật " + body.size() + " điểm dừng",
                "data", tourRouteService.upsertStops(tourId, body)));
    }

    @DeleteMapping("/api/admin/tours/stops/{stopId}")
    public ResponseEntity<?> deleteStop(@PathVariable Integer stopId) {
        tourRouteService.deleteStop(stopId);
        return ResponseEntity.ok(Map.of("success", true, "message", "Đã xóa điểm dừng"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleError(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "success", false, "message", ex.getMessage()));
    }
}

package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.admin.CreateDepartureRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminDepartureDetailResponse;
import com.tourism.tourcatalog.service.AdminDepartureService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/departures")
@RequiredArgsConstructor
public class AdminDepartureController {

    private final AdminDepartureService adminDepartureService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllDepartures(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Boolean status,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Map<String, Object> result = adminDepartureService.getAllDepartures(page, size, status, startDate, endDate);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDepartureById(@PathVariable Integer id) {
        AdminDepartureDetailResponse departure = adminDepartureService.getDepartureById(id);
        return ok(departure);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createDeparture(@RequestBody CreateDepartureRequest request) {
        AdminDepartureDetailResponse departure = adminDepartureService.createDeparture(request);
        return ok(departure);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateDeparture(
            @PathVariable Integer id,
            @RequestBody CreateDepartureRequest request) {
        AdminDepartureDetailResponse departure = adminDepartureService.updateDeparture(id, request);
        return ok(departure);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteDeparture(@PathVariable Integer id) {
        adminDepartureService.deleteDeparture(id);
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Departure deleted successfully");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<Map<String, Object>> cloneDeparture(
            @PathVariable Integer id,
            @RequestParam String newDepartureDate) {
        AdminDepartureDetailResponse departure = adminDepartureService.cloneDeparture(id, newDepartureDate);
        return ok(departure);
    }

    private ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}

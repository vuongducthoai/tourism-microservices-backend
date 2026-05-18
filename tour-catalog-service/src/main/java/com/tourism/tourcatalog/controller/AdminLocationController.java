package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.request.admin.LocationRequest;
import com.tourism.tourcatalog.dto.response.admin.AdminLocationResponse;
import com.tourism.tourcatalog.entity.Region;
import com.tourism.tourcatalog.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/admin/locations")
@RequiredArgsConstructor
@Tag(name = "Admin - Locations", description = "Quản lý địa điểm du lịch")
public class AdminLocationController {

    private final LocationService locationService;

    @Operation(summary = "Danh sách địa điểm (có phân trang + tìm kiếm)")
    @GetMapping
    public ResponseEntity<Page<AdminLocationResponse>> getAllLocations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "locationID") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String region
    ) {
        Sort sort = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(locationService.getAllLocations(pageable, search, region));
    }

    @Operation(summary = "Danh sách địa điểm quốc nội (có sân bay)")
    @GetMapping("/national")
    public ResponseEntity<Page<AdminLocationResponse>> getAirportNational(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "locationID") String sortBy,
            @RequestParam(defaultValue = "DESC") String sortDir
    ) {
        Sort sort = sortDir.equalsIgnoreCase("ASC")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        return ResponseEntity.ok(locationService.getAirportNational(pageable));
    }

    @Operation(summary = "Chi tiết địa điểm theo ID")
    @GetMapping("/{id}")
    public ResponseEntity<AdminLocationResponse> getLocationById(@PathVariable Integer id) {
        return ResponseEntity.ok(locationService.getLocationById(id));
    }

    @Operation(summary = "Tạo địa điểm mới")
    @PostMapping
    public ResponseEntity<AdminLocationResponse> createLocation(
            @Valid @RequestBody LocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(locationService.createLocation(request));
    }

    @Operation(summary = "Cập nhật địa điểm")
    @PutMapping("/{id}")
    public ResponseEntity<AdminLocationResponse> updateLocation(
            @PathVariable Integer id,
            @Valid @RequestBody LocationRequest request) {
        return ResponseEntity.ok(locationService.updateLocation(id, request));
    }

    @Operation(summary = "Xóa địa điểm (soft delete)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLocation(@PathVariable Integer id) {
        locationService.deleteLocation(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Upload ảnh cho địa điểm")
    @PostMapping("/{id}/image")
    public ResponseEntity<String> uploadImage(
            @PathVariable Integer id,
            @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(locationService.uploadImage(id, file));
    }

    @Operation(summary = "Danh sách tất cả vùng miền")
    @GetMapping("/regions")
    public ResponseEntity<List<Region>> getAllRegions() {
        return ResponseEntity.ok(Arrays.asList(Region.values()));
    }
}

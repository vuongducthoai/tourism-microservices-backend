package com.tourism.booking.controller;

import com.tourism.booking.dto.request.CouponRequest;
import com.tourism.booking.dto.response.AdminCouponResponse;
import com.tourism.booking.service.AdminCouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/coupons")
@RequiredArgsConstructor
@Tag(name = "Admin Coupon Management")
public class AdminCouponController {

    private final AdminCouponService adminCouponService;

    @GetMapping
    @Operation(summary = "Lấy danh sách tất cả coupons")
    public ResponseEntity<Page<AdminCouponResponse>> getAllCoupons(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(adminCouponService.getAllCoupons(pageable));
    }

    @GetMapping("/global")
    @Operation(summary = "Lấy danh sách coupons loại GLOBAL")
    public ResponseEntity<Page<AdminCouponResponse>> getGlobalCoupons(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(adminCouponService.getGlobalCoupons(pageable));
    }

    @GetMapping("/departure")
    @Operation(summary = "Lấy danh sách coupons loại DEPARTURE")
    public ResponseEntity<Page<AdminCouponResponse>> getDepartureCoupons(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(adminCouponService.getDepartureCoupons(pageable));
    }

    @GetMapping("/search")
    @Operation(summary = "Tìm kiếm coupons theo mã hoặc mô tả")
    public ResponseEntity<Page<AdminCouponResponse>> searchCoupons(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(adminCouponService.searchCoupons(keyword, pageable));
    }

    @GetMapping("/{couponId}")
    @Operation(summary = "Lấy thông tin chi tiết coupon")
    public ResponseEntity<AdminCouponResponse> getCouponById(@PathVariable Integer couponId) {
        return ResponseEntity.ok(adminCouponService.getCouponById(couponId));
    }

    @PostMapping
    @Operation(summary = "Tạo coupon mới")
    public ResponseEntity<AdminCouponResponse> createCoupon(@Valid @RequestBody CouponRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminCouponService.createCoupon(request));
    }

    @PutMapping("/{couponId}")
    @Operation(summary = "Cập nhật coupon")
    public ResponseEntity<AdminCouponResponse> updateCoupon(
            @PathVariable Integer couponId,
            @Valid @RequestBody CouponRequest request) {
        return ResponseEntity.ok(adminCouponService.updateCoupon(couponId, request));
    }

    @DeleteMapping("/{couponId}")
    @Operation(summary = "Xóa mềm coupon")
    public ResponseEntity<Void> deleteCoupon(@PathVariable Integer couponId) {
        adminCouponService.deleteCoupon(couponId);
        return ResponseEntity.noContent().build();
    }
}

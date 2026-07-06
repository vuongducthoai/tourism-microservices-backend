package com.tourism.booking.feign;

import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.feign.dto.TourBookingInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "tour-catalog-service")
public interface TourCatalogFeignClient {

    @GetMapping("/api/departures/{departureId}")
    DepartureInfoResponse getDepartureInfo(@PathVariable Integer departureId);

    /** Lấy tất cả lịch đang gắn coupon này (để hiển thị/sửa coupon nhiều lịch). */
    @GetMapping("/api/departures/by-coupon/{couponId}")
    List<DepartureInfoResponse> getDeparturesByCoupon(@PathVariable Integer couponId);

    @GetMapping("/api/departures/order-info")
    TourBookingInfoResponse getOrderInfo(
            @RequestParam(required = false) String tourCode,
            @RequestParam Integer departureId);

    @PostMapping("/api/departures/{departureId}/decrease-slots")
    ResponseEntity<Void> decreaseSlots(
            @PathVariable Integer departureId,
            @RequestParam int count);

    @PostMapping("/api/departures/{departureId}/increase-slots")
    ResponseEntity<Void> increaseSlots(
            @PathVariable Integer departureId,
            @RequestParam int count);

    /** Gắn coupon cho các lịch được chọn (gỡ lịch cũ, gắn lịch mới). */
    @PutMapping("/api/departures/coupon-assignment")
    ResponseEntity<Void> assignCouponToDepartures(
            @RequestParam Integer couponId,
            @RequestBody List<Integer> departureIds);

    /** Gỡ coupon khỏi tất cả lịch. */
    @DeleteMapping("/api/departures/coupon-assignment/{couponId}")
    ResponseEntity<Void> clearCouponFromDepartures(@PathVariable Integer couponId);
}

package com.tourism.booking.feign;

import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.feign.dto.TourBookingInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "tour-catalog-service")
public interface TourCatalogFeignClient {

    @GetMapping("/api/departures/{departureId}")
    DepartureInfoResponse getDepartureInfo(@PathVariable Integer departureId);

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
}

package com.tourism.booking.feign;

import com.tourism.booking.feign.dto.DepartureInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "tour-catalog-service")
public interface TourCatalogFeignClient {

    @GetMapping("/api/departures/{departureId}")
    DepartureInfoResponse getDepartureInfo(@PathVariable Integer departureId);
}

package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.DepartureInfoResponse;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.repository.TourDepartureRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/departures")
@RequiredArgsConstructor
@Tag(name = "Departures", description = "[Internal] Lịch khởi hành — booking-service truy vấn thông tin chuyến đi qua Feign")
public class DepartureController {

    private final TourDepartureRepository tourDepartureRepository;

    @Operation(summary = "[Internal] Lấy thông tin departure", description = "Trả về tour + departure info theo departureId — dung nội bộ")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thông tin departure"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy departure")
    })
    @GetMapping("/{departureId}")
    public ResponseEntity<DepartureInfoResponse> getDepartureInfo(@PathVariable Integer departureId) {
        TourDeparture dep = tourDepartureRepository.findById(departureId)
                .orElseThrow(() -> new RuntimeException("Departure not found: " + departureId));

        DepartureInfoResponse res = new DepartureInfoResponse();
        res.setDepartureID(dep.getDepartureID());
        if (dep.getDepartureDate() != null) {
            res.setDepartureDate(dep.getDepartureDate().toString());
        }
        if (dep.getTour() != null) {
            var tour = dep.getTour();
            res.setTourID(tour.getTourID());
            res.setTourCode(tour.getTourCode());
            res.setTourName(tour.getTourName());
            res.setDuration(tour.getDuration());
            if (tour.getImages() != null && !tour.getImages().isEmpty()) {
                res.setImage(tour.getImages().get(0).getImageUrl());
            }
        }
        return ResponseEntity.ok(res);
    }
}

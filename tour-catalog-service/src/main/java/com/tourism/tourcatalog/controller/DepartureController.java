package com.tourism.tourcatalog.controller;

import com.tourism.tourcatalog.dto.response.DepartureInfoResponse;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.repository.TourDepartureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal endpoint — used by booking-service via Feign to resolve departure info.
 */
@RestController
@RequestMapping("/api/departures")
@RequiredArgsConstructor
public class DepartureController {

    private final TourDepartureRepository tourDepartureRepository;

    /**
     * GET /api/departures/{departureId}
     * Returns tour + departure info for a given departure ID.
     * Called internally by booking-service.
     */
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

package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.TourStopRequest;
import com.tourism.tourcatalog.dto.response.TourRouteResponse;
import com.tourism.tourcatalog.dto.response.TourStopResponse;
import com.tourism.tourcatalog.entity.ItineraryDay;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourStop;
import com.tourism.tourcatalog.repository.ItineraryDayRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.repository.TourStopRepository;
import com.tourism.tourcatalog.service.TourRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TourRouteServiceImpl implements TourRouteService {

    private final TourStopRepository tourStopRepository;
    private final TourRepository tourRepository;
    private final ItineraryDayRepository itineraryDayRepository;

    @Override
    @Transactional(readOnly = true)
    public TourRouteResponse getRoute(String tourCode) {
        List<TourStop> stops = tourStopRepository.findByTourCodeOrdered(tourCode);
        List<TourStopResponse> dto = stops.stream().map(this::mapToResponse).toList();

        if (dto.isEmpty()) {
            return TourRouteResponse.builder()
                    .tourCode(tourCode)
                    .stops(List.of())
                    .availableDays(List.of())
                    .build();
        }

        Double minLat = dto.stream().mapToDouble(TourStopResponse::getLatitude).min().orElse(0);
        Double maxLat = dto.stream().mapToDouble(TourStopResponse::getLatitude).max().orElse(0);
        Double minLng = dto.stream().mapToDouble(TourStopResponse::getLongitude).min().orElse(0);
        Double maxLng = dto.stream().mapToDouble(TourStopResponse::getLongitude).max().orElse(0);

        List<Integer> days = dto.stream()
                .map(TourStopResponse::getDayNumber)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        return TourRouteResponse.builder()
                .tourCode(tourCode)
                .stops(dto)
                .minLat(minLat).maxLat(maxLat).minLng(minLng).maxLng(maxLng)
                .availableDays(days)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TourStopResponse> getStopsByTourId(Integer tourId) {
        return tourStopRepository.findByTourIdOrdered(tourId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<TourStopResponse> upsertStops(Integer tourId, List<TourStopRequest> requests) {
        Tour tour = tourRepository.findById(tourId)
                .orElseThrow(() -> new RuntimeException("Tour không tồn tại: " + tourId));

        // Xóa hết stop cũ — đơn giản hơn diff-based update
        tourStopRepository.deleteByTour_TourID(tourId);

        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        // Cache theo id và theo dayNumber để tránh N+1
        Map<Integer, ItineraryDay> dayByIdCache = new HashMap<>();
        Map<Integer, ItineraryDay> dayByNumberCache = new HashMap<>();
        // Preload all days của tour 1 lần
        for (ItineraryDay d : itineraryDayRepository.findByTourIdOrdered(tourId)) {
            dayByIdCache.put(d.getItineraryDayID(), d);
            if (d.getDayNumber() != null) dayByNumberCache.put(d.getDayNumber(), d);
        }

        List<TourStop> saved = new ArrayList<>();
        for (TourStopRequest req : requests) {
            ItineraryDay day = null;
            if (req.getItineraryDayId() != null) {
                day = dayByIdCache.get(req.getItineraryDayId());
            } else if (req.getDayNumber() != null) {
                day = dayByNumberCache.get(req.getDayNumber());
            }
            TourStop stop = TourStop.builder()
                    .tour(tour)
                    .itineraryDay(day)
                    .name(req.getName().trim())
                    .latitude(req.getLatitude())
                    .longitude(req.getLongitude())
                    .stopOrder(req.getStopOrder())
                    .description(req.getDescription())
                    .stopType(req.getStopType())
                    .build();
            saved.add(tourStopRepository.save(stop));
        }
        return saved.stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteStop(Integer stopId) {
        tourStopRepository.deleteById(stopId);
    }

    private TourStopResponse mapToResponse(TourStop s) {
        ItineraryDay d = s.getItineraryDay();
        return TourStopResponse.builder()
                .stopId(s.getStopId())
                .name(s.getName())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .stopOrder(s.getStopOrder())
                .description(s.getDescription())
                .stopType(s.getStopType())
                .dayNumber(d != null ? d.getDayNumber() : null)
                .dayTitle(d != null ? d.getTitle() : null)
                .build();
    }
}

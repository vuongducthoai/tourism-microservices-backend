package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.response.CombinedItineraryRouteResponse;
import com.tourism.tourcatalog.dto.response.DayWithStopsResponse;
import com.tourism.tourcatalog.dto.response.StopWithIndexResponse;
import com.tourism.tourcatalog.entity.ItineraryDay;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourStop;
import com.tourism.tourcatalog.repository.ItineraryDayRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.repository.TourStopRepository;
import com.tourism.tourcatalog.service.ItineraryRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ItineraryRouteServiceImpl implements ItineraryRouteService {

    private final TourRepository tourRepository;
    private final ItineraryDayRepository dayRepository;
    private final TourStopRepository stopRepository;

    private static final String[] DAY_COLORS = {
            "#1e40af", "#dc2626", "#059669", "#d97706", "#7c3aed", "#0891b2"
    };

    @Override
    @Transactional(readOnly = true)
    public CombinedItineraryRouteResponse getCombined(String tourCode) {
        Tour tour = tourRepository.findDetailByTourCode(tourCode)
                .orElseThrow(() -> new RuntimeException("Tour không tồn tại: " + tourCode));

        List<ItineraryDay> days = dayRepository.findByTourIdOrdered(tour.getTourID());
        List<TourStop> stops = stopRepository.findByTourCodeOrdered(tourCode);

        // Gán globalIndex 1..N theo thứ tự stops (đã sort COALESCE(dayNumber,0), stopOrder ở repo)
        AtomicInteger gi = new AtomicInteger(1);
        Map<Integer, List<StopWithIndexResponse>> stopsByDayNumber = new LinkedHashMap<>();
        List<StopWithIndexResponse> orphans = new ArrayList<>();

        for (TourStop s : stops) {
            Integer dn = s.getItineraryDay() != null ? s.getItineraryDay().getDayNumber() : null;
            StopWithIndexResponse dto = mapStop(s, gi.getAndIncrement());
            if (dn == null) {
                orphans.add(dto);
            } else {
                stopsByDayNumber.computeIfAbsent(dn, k -> new ArrayList<>()).add(dto);
            }
        }

        // Build day blocks
        List<DayWithStopsResponse> dayBlocks = days.stream()
                .map(d -> {
                    List<StopWithIndexResponse> dayStops = stopsByDayNumber
                            .getOrDefault(d.getDayNumber(), List.of());
                    return DayWithStopsResponse.builder()
                            .itineraryDayId(d.getItineraryDayID())
                            .dayNumber(d.getDayNumber())
                            .title(d.getTitle())
                            .autoSubtitle(buildAutoSubtitle(dayStops))
                            .meals(d.getMeals())
                            .details(d.getDetails())
                            .color(dayColor(d.getDayNumber()))
                            .stops(dayStops)
                            .build();
                })
                .collect(Collectors.toList());

        // Day có trong itinerary nhưng list stop rỗng — admin cần thêm pin
        List<Integer> missing = dayBlocks.stream()
                .filter(b -> b.getStops().isEmpty())
                .map(DayWithStopsResponse::getDayNumber)
                .collect(Collectors.toList());

        Double minLat = null, maxLat = null, minLng = null, maxLng = null;
        if (!stops.isEmpty()) {
            minLat = stops.stream().mapToDouble(TourStop::getLatitude).min().orElse(0);
            maxLat = stops.stream().mapToDouble(TourStop::getLatitude).max().orElse(0);
            minLng = stops.stream().mapToDouble(TourStop::getLongitude).min().orElse(0);
            maxLng = stops.stream().mapToDouble(TourStop::getLongitude).max().orElse(0);
        }

        return CombinedItineraryRouteResponse.builder()
                .tourCode(tourCode)
                .days(dayBlocks)
                .orphanStops(orphans)
                .missingStopDays(missing)
                .minLat(minLat).maxLat(maxLat).minLng(minLng).maxLng(maxLng)
                .build();
    }

    /** Compute subtitle: "Cảng Tuần Châu → Hang Sửng Sốt → Hang Luồn". */
    private String buildAutoSubtitle(List<StopWithIndexResponse> dayStops) {
        if (dayStops == null || dayStops.isEmpty()) return null;
        return dayStops.stream()
                .map(StopWithIndexResponse::getName)
                .filter(Objects::nonNull)
                .filter(n -> !n.isBlank())
                .collect(Collectors.joining(" → "));
    }

    private StopWithIndexResponse mapStop(TourStop s, int globalIndex) {
        return StopWithIndexResponse.builder()
                .stopId(s.getStopId())
                .name(s.getName())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .stopOrder(s.getStopOrder())
                .globalIndex(globalIndex)
                .description(s.getDescription())
                .stopType(s.getStopType())
                .build();
    }

    private static String dayColor(Integer n) {
        if (n == null || n < 1) return "#64748b";
        return DAY_COLORS[(n - 1) % DAY_COLORS.length];
    }
}

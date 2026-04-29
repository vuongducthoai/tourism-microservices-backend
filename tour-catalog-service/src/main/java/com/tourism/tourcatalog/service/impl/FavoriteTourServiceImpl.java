package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.response.DepartureDateItem;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.FavoriteTour;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.repository.FavoriteTourRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.FavoriteTourService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FavoriteTourServiceImpl implements FavoriteTourService {

    private final FavoriteTourRepository favoriteTourRepository;
    private final TourRepository tourRepository;

    @Override
    @Transactional(readOnly = true)
    public List<TourSearchResponse> getFavoriteTours(Integer userId) {
        List<FavoriteTour> favorites = favoriteTourRepository.findByUserIdWithTourDetails(userId);
        return favorites.stream()
                .map(f -> toSearchResponse(f.getTour(), true))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void addFavoriteTour(Integer userId, Integer tourId) {
        if (favoriteTourRepository.existsByUserIdAndTour_TourID(userId, tourId)) {
            return; // already favorited
        }
        Tour tour = tourRepository.findById(tourId)
                .orElseThrow(() -> new RuntimeException("Tour not found: " + tourId));
        FavoriteTour fav = new FavoriteTour();
        fav.setUserId(userId);
        fav.setTour(tour);
        favoriteTourRepository.save(fav);
    }

    @Override
    @Transactional
    public void removeFavoriteTour(Integer userId, Integer tourId) {
        favoriteTourRepository.deleteByUserIdAndTourId(userId, tourId);
    }

    // ── helper ──────────────────────────────────────────────────────────────

    private TourSearchResponse toSearchResponse(Tour tour, boolean isFavorite) {
        TourSearchResponse res = new TourSearchResponse();
        res.setTourID(tour.getTourID());
        res.setTourCode(tour.getTourCode());
        res.setTourName(tour.getTourName());
        res.setTransportation(tour.getTransportation());
        res.setDuration(tour.getDuration());
        res.setIsFavorite(isFavorite);

        if (tour.getStartLocation() != null) {
            res.setStartPointName(tour.getStartLocation().getName());
        }
        if (tour.getImages() != null && !tour.getImages().isEmpty()) {
            res.setImage(tour.getImages().get(0).getImageUrl());
        }

        List<DepartureDateItem> departureDates = new ArrayList<>();
        BigDecimal minAdultPrice = null;
        LocalDate today = LocalDate.now();

        if (tour.getDepartures() != null) {
            for (TourDeparture dep : tour.getDepartures()) {
                if (!Boolean.TRUE.equals(dep.getStatus())) continue;
                if (dep.getDepartureDate() == null) continue;
                LocalDate depDate = dep.getDepartureDate().toLocalDate();
                if (depDate.isBefore(today)) continue;

                departureDates.add(DepartureDateItem.builder()
                        .departureID(dep.getDepartureID())
                        .departureDate(depDate.toString())
                        .build());

                if (dep.getPricings() != null) {
                    for (DeparturePricing p : dep.getPricings()) {
                        if ("ADULT".equals(p.getPassengerType()) && p.getSalePrice() != null) {
                            if (minAdultPrice == null || p.getSalePrice().compareTo(minAdultPrice) < 0) {
                                minAdultPrice = p.getSalePrice();
                            }
                        }
                    }
                }
            }
        }

        departureDates.sort(Comparator.comparing(DepartureDateItem::getDepartureDate));
        res.setDepartureDates(departureDates);
        res.setMoney(minAdultPrice != null ? minAdultPrice.longValue() : 0L);
        return res;
    }
}

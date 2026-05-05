package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.TourChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.dto.response.TourSpecialResponse;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.entity.TourImage;
import com.tourism.tourcatalog.repository.ReviewRepository;
import com.tourism.tourcatalog.repository.TourDepartureRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.TourService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourServiceImpl implements TourService {

    private final TourRepository          tourRepository;
    private final TourDepartureRepository departureRepository;
    private final ReviewRepository        reviewRepository;
    private final ModelMapper             modelMapper;

    /**
     * GET /api/tours/display
     */
    @Override
    @Transactional(readOnly = true)
    public List<TourDisplayResponse> getAllToursForDisplay() {
        List<Tour> tours = tourRepository.findAllActiveWithDetails();
        return tours.stream()
                .map(t -> modelMapper.map(t, TourDisplayResponse.class))
                .collect(Collectors.toList());
    }

    /**
     * GET /api/tours/deepest-discount
     */
    @Override
    @Transactional(readOnly = true)
    public List<TourSpecialResponse> getTop10DeepestDiscountTours() {
        List<TourDeparture> departures =
                departureRepository.findActiveDiscountedDepartures(LocalDateTime.now());

        return departures.stream()
                .map(d -> modelMapper.map(d, TourSpecialResponse.class))
                .filter(r -> r.getDiscountPercentage() != null && r.getDiscountPercentage() > 0)
                .collect(Collectors.toMap(
                        TourSpecialResponse::getTourCode,
                        r -> r,
                        (existing, replacement) ->
                                existing.getDiscountPercentage() >= replacement.getDiscountPercentage()
                                        ? existing : replacement
                ))
                .values().stream()
                .sorted(Comparator.comparingInt(TourSpecialResponse::getDiscountPercentage).reversed())
                .limit(10)
                .collect(Collectors.toList());
    }

    /**
     * GET /api/tours/search
     */
    @Override
    @Transactional(readOnly = true)
    public List<TourSearchResponse> searchTours(SearchToursRequest request) {
        List<Tour> tours = tourRepository.searchToursDynamically(request);
        return tours.stream()
                .map(t -> modelMapper.map(t, TourSearchResponse.class))
                .filter(r -> r.getDepartureDates() != null && !r.getDepartureDates().isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * GET /api/tours/chatbot-sync
     *
     * Trả về toàn bộ tour active với departures + pricings để analytics-service
     * sync lên Pinecone Vector DB.
     *
     * Mỗi TourChatbotSyncResponse chứa:
     *  - Thông tin tour cơ bản
     *  - imageUrl: lấy ảnh đầu tiên trong danh sách images
     *  - avgRating, reviewCount: tính từ ReviewRepository
     *  - departures: chỉ lấy departure active, ngày >= hôm nay
     *    - adultSalePrice / adultOriginalPrice: từ DeparturePricing type ADULT
     */
    @Override
    @Transactional(readOnly = true)
    public List<TourChatbotSyncResponse> getAllToursForChatbotSync() {
        List<Tour> tours = tourRepository.findAllActiveWithDetails();
        LocalDateTime now = LocalDateTime.now();
        List<TourChatbotSyncResponse> result = new ArrayList<>();

        for (Tour tour : tours) {
            // Image URL
            String imageUrl = null;
            if (tour.getImages() != null && !tour.getImages().isEmpty()) {
                imageUrl = tour.getImages().get(0).getImageUrl();
            }

            // Rating stats
            Double avgRating   = reviewRepository.getAverageRatingByTourCode(tour.getTourCode());
            Integer reviewCount = reviewRepository.countByTourCode(tour.getTourCode());

            // Departures
            List<TourChatbotSyncResponse.DepartureSyncResponse> departureDTOs = new ArrayList<>();
            if (tour.getDepartures() != null) {
                for (TourDeparture dep : tour.getDepartures()) {
                    if (dep.getDepartureDate() == null) continue;
                    if (dep.getDepartureDate().isBefore(now)) continue;
                    if (!Boolean.TRUE.equals(dep.getStatus())) continue;

                    // ADULT pricing
                    Double salePrice     = null;
                    Double originalPrice = null;
                    if (dep.getPricings() != null) {
                        for (DeparturePricing p : dep.getPricings()) {
                            if ("ADULT".equals(p.getPassengerType())) {
                                salePrice     = p.getSalePrice()     != null ? p.getSalePrice().doubleValue()     : null;
                                originalPrice = p.getOriginalPrice() != null ? p.getOriginalPrice().doubleValue() : null;
                                break;
                            }
                        }
                    }

                    departureDTOs.add(TourChatbotSyncResponse.DepartureSyncResponse.builder()
                            .departureID(dep.getDepartureID())
                            .departureDate(dep.getDepartureDate().toLocalDate().toString())
                            .availableSlots(dep.getAvailableSlots())
                            .adultSalePrice(salePrice)
                            .adultOriginalPrice(originalPrice)
                            // coupon fields left null — coupon data lives in booking-service
                            .couponDiscount(null)
                            .couponCode(null)
                            .couponStartDate(null)
                            .couponEndDate(null)
                            .build());
                }
            }

            result.add(TourChatbotSyncResponse.builder()
                    .tourID(tour.getTourID())
                    .tourCode(tour.getTourCode())
                    .tourName(tour.getTourName())
                    .duration(tour.getDuration())
                    .transportation(tour.getTransportation())
                    .startLocationName(tour.getStartLocation() != null ? tour.getStartLocation().getName() : null)
                    .startLocationID(tour.getStartLocation() != null ? tour.getStartLocation().getLocationID() : null)
                    .endLocationName(tour.getEndLocation() != null ? tour.getEndLocation().getName() : null)
                    .endLocationID(tour.getEndLocation() != null ? tour.getEndLocation().getLocationID() : null)
                    .attractions(tour.getAttractions())
                    .meals(tour.getMeals())
                    .hotel(tour.getHotel())
                    .imageUrl(imageUrl)
                    .avgRating(avgRating)
                    .reviewCount(reviewCount)
                    .departures(departureDTOs)
                    .build());
        }

        return result;
    }
}


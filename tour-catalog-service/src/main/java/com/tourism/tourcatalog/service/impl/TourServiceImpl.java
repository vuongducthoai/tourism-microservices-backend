package com.tourism.tourcatalog.service.impl;

import com.tourism.tourcatalog.dto.request.SearchToursRequest;
import com.tourism.tourcatalog.dto.response.BranchContactResponse;
import com.tourism.tourcatalog.dto.response.DepartureDetailResponse;
import com.tourism.tourcatalog.dto.response.DeparturePricingResponse;
import com.tourism.tourcatalog.dto.response.DepartureTransportResponse;
import com.tourism.tourcatalog.dto.response.ItineraryDayResponse;
import com.tourism.tourcatalog.dto.response.PolicyTemplateResponse;
import com.tourism.tourcatalog.dto.response.RelatedTourResponse;
import com.tourism.tourcatalog.dto.response.TourChatbotSyncResponse;
import com.tourism.tourcatalog.dto.response.TourDetailResponse;
import com.tourism.tourcatalog.dto.response.TourDisplayResponse;
import com.tourism.tourcatalog.dto.response.TourSearchResponse;
import com.tourism.tourcatalog.dto.response.TourSpecialResponse;
import com.tourism.tourcatalog.entity.BranchContact;
import com.tourism.tourcatalog.entity.DeparturePricing;
import com.tourism.tourcatalog.entity.DepartureTransport;
import com.tourism.tourcatalog.entity.PolicyTemplate;
import com.tourism.tourcatalog.entity.Tour;
import com.tourism.tourcatalog.entity.TourDeparture;
import com.tourism.tourcatalog.entity.TourMedia;
import com.tourism.tourcatalog.entity.TransportType;
import com.tourism.tourcatalog.feign.BookingFeignClient;
import com.tourism.tourcatalog.feign.dto.CouponBriefResponse;
import com.tourism.tourcatalog.repository.FavoriteTourRepository;
import com.tourism.tourcatalog.repository.LocationRepository;
import com.tourism.tourcatalog.repository.ReviewRepository;
import com.tourism.tourcatalog.repository.TourDepartureRepository;
import com.tourism.tourcatalog.repository.TourRepository;
import com.tourism.tourcatalog.service.TourService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TourServiceImpl implements TourService {

    private final TourRepository          tourRepository;
    private final TourDepartureRepository departureRepository;
    private final ReviewRepository        reviewRepository;
    private final FavoriteTourRepository  favoriteTourRepository;
    private final LocationRepository      locationRepository;
    private final BookingFeignClient      bookingFeignClient;
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

        Set<Integer> favoriteTourIds = new HashSet<>();
        if (request.getUserId() != null) {
            favoriteTourIds = favoriteTourRepository
                    .findByUserIdWithTourDetails(request.getUserId())
                    .stream()
                    .map(f -> f.getTour().getTourID())
                    .collect(Collectors.toSet());
        }
        final Set<Integer> finalFavIds = favoriteTourIds;

        return tours.stream()
                .map(t -> {
                    TourSearchResponse r = modelMapper.map(t, TourSearchResponse.class);
                    r.setIsFavorite(finalFavIds.contains(t.getTourID()));
                    return r;
                })
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

    @Override
    @Transactional(readOnly = true)
    public TourDetailResponse getTourDetailByCode(String tourCode) {
        Tour tour = tourRepository.findDetailByTourCode(tourCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tour not found: " + tourCode));

        LocalDateTime now = LocalDateTime.now();

        // Lọc và build departures hợp lệ
        List<DepartureDetailResponse> departureDTOs = new ArrayList<>();
        if (tour.getDepartures() != null) {
            List<TourDeparture> validDepartures = tour.getDepartures().stream()
                    .filter(d -> Boolean.TRUE.equals(d.getStatus()))
                    .filter(d -> {
                        // Check if departure is in the future:
                        // 1) If there's an OUTBOUND transport, use its departTime
                        // 2) If transports have null type, use any transport's departTime
                        // 3) Fall back to departure date itself
                        if (d.getTransports() != null && !d.getTransports().isEmpty()) {
                            boolean hasOutbound = d.getTransports().stream()
                                    .anyMatch(t -> TransportType.OUTBOUND.equals(t.getTransportType()));
                            if (hasOutbound) {
                                return d.getTransports().stream()
                                        .filter(t -> TransportType.OUTBOUND.equals(t.getTransportType()))
                                        .anyMatch(t -> t.getDepartTime() != null && t.getDepartTime().isAfter(now));
                            }
                            // transports exist but type is null — use any transport's departTime
                            boolean anyFuture = d.getTransports().stream()
                                    .anyMatch(t -> t.getDepartTime() != null && t.getDepartTime().isAfter(now));
                            if (anyFuture) return true;
                        }
                        // fallback: use departure date
                        return d.getDepartureDate() != null && d.getDepartureDate().isAfter(now);
                    })
                    .sorted(Comparator.comparing(TourDeparture::getDepartureDate))
                    .collect(Collectors.toList());

            for (TourDeparture dep : validDepartures) {
                // Lấy ADULT pricing
                BigDecimal adultSalePrice = BigDecimal.ZERO;
                if (dep.getPricings() != null) {
                    adultSalePrice = dep.getPricings().stream()
                            .filter(p -> "ADULT".equals(p.getPassengerType()))
                            .map(DeparturePricing::getSalePrice)
                            .filter(p -> p != null)
                            .findFirst()
                            .orElse(BigDecimal.ZERO);
                }

                // Departure-specific coupon
                String departureCouponCode = null;
                BigDecimal departureCouponDiscount = BigDecimal.ZERO;
                if (dep.getCouponId() != null) {
                    try {
                        CouponBriefResponse depCoupon = bookingFeignClient.getCouponByDepartureId(dep.getCouponId());
                        if (depCoupon != null) {
                            departureCouponCode = depCoupon.getCouponCode();
                            departureCouponDiscount = depCoupon.getDiscountAmount() != null
                                    ? BigDecimal.valueOf(depCoupon.getDiscountAmount()) : BigDecimal.ZERO;
                        }
                    } catch (Exception ignored) {}
                }

                // Global coupon — tính trên giá sau khi trừ departure coupon
                BigDecimal priceAfterDepartureCoupon = adultSalePrice.subtract(departureCouponDiscount);
                String globalCouponCode = null;
                BigDecimal globalCouponDiscount = BigDecimal.ZERO;
                try {
                    CouponBriefResponse globalCoupon = bookingFeignClient.getBestGlobalCoupon(priceAfterDepartureCoupon);
                    if (globalCoupon != null) {
                        globalCouponCode = globalCoupon.getCouponCode();
                        globalCouponDiscount = globalCoupon.getDiscountAmount() != null
                                ? BigDecimal.valueOf(globalCoupon.getDiscountAmount()) : BigDecimal.ZERO;
                    }
                } catch (Exception ignored) {}

                BigDecimal totalDiscount = departureCouponDiscount.add(globalCouponDiscount);

                // Build pricings
                List<DeparturePricingResponse> pricingDTOs = new ArrayList<>();
                if (dep.getPricings() != null) {
                    for (DeparturePricing p : dep.getPricings()) {
                        BigDecimal finalPrice = p.getSalePrice() != null ? p.getSalePrice() : BigDecimal.ZERO;
                        if ("ADULT".equals(p.getPassengerType())) {
                            finalPrice = finalPrice.subtract(totalDiscount).max(BigDecimal.ZERO);
                        }
                        pricingDTOs.add(DeparturePricingResponse.builder()
                                .passengerType(p.getPassengerType())
                                .description(p.getAgeDescription())
                                .originalPrice(p.getOriginalPrice())
                                .salePrice(p.getSalePrice())
                                .finalPrice(finalPrice)
                                .build());
                    }
                }

                // Build transports
                List<DepartureTransportResponse> transportDTOs = new ArrayList<>();
                if (dep.getTransports() != null) {
                    for (DepartureTransport t : dep.getTransports()) {
                        String startPointName = resolveAirportName(t.getStartPoint());
                        String endPointName = resolveAirportName(t.getEndPoint());
                        transportDTOs.add(DepartureTransportResponse.builder()
                                .type(t.getTransportType() != null ? t.getTransportType().name() : null)
                                .transportCode(t.getVehicleName())
                                .vehicleType(t.getVehicleType() != null ? t.getVehicleType().name() : null)
                                .vehicleName(t.getVehicleName())
                                .startPoint(t.getStartPoint())
                                .startPointName(startPointName)
                                .endPoint(t.getEndPoint())
                                .endPointName(endPointName)
                                .departTime(t.getDepartTime() != null ? t.getDepartTime().toString() : null)
                                .arrivalTime(t.getArrivalTime() != null ? t.getArrivalTime().toString() : null)
                                .build());
                    }
                }

                String departureDateStr = dep.getDepartureDate() != null
                        ? dep.getDepartureDate().toLocalDate().toString() : null;

                departureDTOs.add(DepartureDetailResponse.builder()
                        .departureId(dep.getDepartureID())
                        .departureDate(departureDateStr)
                        .availableSlots(dep.getAvailableSlots())
                        .pricings(pricingDTOs)
                        .transports(transportDTOs)
                        .departureCouponCode(departureCouponCode)
                        .departureCouponDiscount(departureCouponDiscount)
                        .globalCouponCode(globalCouponCode)
                        .globalCouponDiscount(globalCouponDiscount)
                        .totalDiscountAmount(totalDiscount)
                        .build());
            }
        }

        // Images
        List<String> images = new ArrayList<>();
        if (tour.getImages() != null) {
            tour.getImages().forEach(img -> {
                if (img.getImageUrl() != null) images.add(img.getImageUrl());
            });
        }

        // Video URL
        String videoUrl = null;
        if (tour.getMediaList() != null) {
            videoUrl = tour.getMediaList().stream()
                    .filter(m -> m.getMediaUrl() != null && isVideoUrl(m.getMediaUrl()))
                    .sorted(Comparator.comparing(
                        (TourMedia m) -> Boolean.TRUE.equals(m.getIsPrimary()) ? 0 : 1))
                    .findFirst()
                    .map(TourMedia::getMediaUrl)
                    .orElse(null);
        }

        // Itinerary
        List<ItineraryDayResponse> itinerary = new ArrayList<>();
        if (tour.getItineraryDays() != null) {
            tour.getItineraryDays().stream()
                    .sorted(Comparator.comparing(day -> day.getDayNumber() != null ? day.getDayNumber() : 0))
                    .forEach(day -> itinerary.add(ItineraryDayResponse.builder()
                            .dayNumber(day.getDayNumber())
                            .title(day.getTitle())
                            .details(day.getDetails())
                            .meals(day.getMeals())
                            .build()));
        }

        // Policy + BranchContact từ departure đầu tiên có policy
        PolicyTemplateResponse policyDTO = null;
        BranchContactResponse branchContactDTO = null;
        if (tour.getDepartures() != null) {
            Optional<TourDeparture> depWithPolicy = tour.getDepartures().stream()
                    .filter(d -> d.getPolicyTemplate() != null)
                    .findFirst();
            if (depWithPolicy.isPresent()) {
                PolicyTemplate pt = depWithPolicy.get().getPolicyTemplate();
                policyDTO = PolicyTemplateResponse.builder()
                        .templateName(pt.getTemplateName())
                        .tourPriceIncludes(pt.getTourPriceIncludes())
                        .tourPriceExcludes(pt.getTourPriceExcludes())
                        .childPricingNotes(pt.getChildPricingNotes())
                        .paymentConditions(pt.getPaymentConditions())
                        .registrationConditions(pt.getRegistrationConditions())
                        .regularDayCancellationRules(pt.getRegularDayCancellationRules())
                        .holidayCancellationRules(pt.getHolidayCancellationRules())
                        .forceMajeureRules(pt.getForceMajeureRules())
                        .packingList(pt.getPackingList())
                        .build();
                BranchContact contact = pt.getContact();
                if (contact != null) {
                    branchContactDTO = BranchContactResponse.builder()
                            .branchName(contact.getBranchName())
                            .address(contact.getAddress())
                            .phone(contact.getPhone())
                            .email(contact.getEmail())
                            .isHeadOffice(contact.getIsHeadOffice())
                            .build();
                }
            }
        }

        // Header pricing từ departure[0] ADULT
        BigDecimal headerOriginalPrice = null;
        BigDecimal headerSalePrice = null;
        Integer totalDiscountPercentage = null;
        if (!departureDTOs.isEmpty()) {
            DepartureDetailResponse firstDep = departureDTOs.get(0);
            if (firstDep.getPricings() != null) {
                Optional<DeparturePricingResponse> adultPricing = firstDep.getPricings().stream()
                        .filter(p -> "ADULT".equals(p.getPassengerType()))
                        .findFirst();
                if (adultPricing.isPresent()) {
                    headerOriginalPrice = adultPricing.get().getOriginalPrice();
                    headerSalePrice = adultPricing.get().getSalePrice();
                    BigDecimal finalP = adultPricing.get().getFinalPrice();
                    if (headerOriginalPrice != null && headerOriginalPrice.compareTo(BigDecimal.ZERO) > 0 && finalP != null) {
                        double pct = headerOriginalPrice.subtract(finalP).doubleValue() / headerOriginalPrice.doubleValue() * 100;
                        totalDiscountPercentage = (int) Math.round(pct);
                    }
                }
            }
        }

        return TourDetailResponse.builder()
                .tourId(tour.getTourID())
                .tourCode(tour.getTourCode())
                .tourName(tour.getTourName())
                .duration(tour.getDuration())
                .transportation(tour.getTransportation())
                .startLocation(tour.getStartLocation() != null ? tour.getStartLocation().getName() : null)
                .endLocation(tour.getEndLocation() != null ? tour.getEndLocation().getName() : null)
                .attractions(tour.getAttractions())
                .meals(tour.getMeals())
                .suitableCustomer(tour.getSuitableCustomer())
                .idealTime(tour.getIdealTime())
                .tripTransportation(tour.getTripTransportation())
                .hotel(tour.getHotel())
                .images(images)
                .videoUrl(videoUrl)
                .itinerary(itinerary)
                .departures(departureDTOs)
                .policy(policyDTO)
                .branchContact(branchContactDTO)
                .originalPrice(headerOriginalPrice)
                .salePrice(headerSalePrice)
                .totalDiscountPercentage(totalDiscountPercentage)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RelatedTourResponse> getRelatedTours(String tourCode) {
        Tour tour = tourRepository.findDetailByTourCode(tourCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tour not found: " + tourCode));

        if (tour.getEndLocation() == null) return new ArrayList<>();

        List<Tour> relatedTours = tourRepository.findRelatedTours(
                tour.getEndLocation().getLocationID(),
                tour.getTourID(),
                PageRequest.of(0, 3)
        );

        List<RelatedTourResponse> result = new ArrayList<>();
        for (Tour related : relatedTours) {
            String image = null;
            if (related.getImages() != null && !related.getImages().isEmpty()) {
                image = related.getImages().get(0).getImageUrl();
            }

            BigDecimal lowestSalePrice = null;
            BigDecimal lowestOriginalPrice = null;
            if (related.getDepartures() != null) {
                for (TourDeparture dep : related.getDepartures()) {
                    if (dep.getPricings() == null) continue;
                    for (DeparturePricing p : dep.getPricings()) {
                        if ("ADULT".equals(p.getPassengerType()) && p.getSalePrice() != null) {
                            if (lowestSalePrice == null || p.getSalePrice().compareTo(lowestSalePrice) < 0) {
                                lowestSalePrice = p.getSalePrice();
                                lowestOriginalPrice = p.getOriginalPrice();
                            }
                        }
                    }
                }
            }

            result.add(RelatedTourResponse.builder()
                    .tourId(related.getTourID())
                    .tourCode(related.getTourCode())
                    .tourName(related.getTourName())
                    .startLocation(related.getStartLocation() != null ? related.getStartLocation().getName() : null)
                    .duration(related.getDuration())
                    .price(lowestSalePrice)
                    .originalPrice(lowestOriginalPrice)
                    .image(image)
                    .build());
        }
        return result;
    }

    private String resolveAirportName(String airportCode) {
        if (airportCode == null) return null;
        return locationRepository.findByAirportCode(airportCode)
                .map(l -> l.getAirportName() != null ? l.getAirportName() : l.getName())
                .orElse(airportCode);
    }

    private static boolean isVideoUrl(String url){
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("/video/upload/")   // Cloudinary video URL pattern
                || lower.endsWith(".mp4")
                || lower.endsWith(".webm")
                || lower.endsWith(".mov")
                || lower.endsWith(".mkv");
    }
}

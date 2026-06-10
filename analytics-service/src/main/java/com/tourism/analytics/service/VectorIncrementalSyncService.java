package com.tourism.analytics.service;

import com.tourism.analytics.dto.feign.CouponSyncDTO;
import com.tourism.analytics.dto.feign.LocationSyncDTO;
import com.tourism.analytics.dto.feign.ReviewSyncDTO;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import com.tourism.analytics.dto.sync.ChatbotSyncEventDTO;
import com.tourism.analytics.feign.BookingFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VectorIncrementalSyncService {

    private final TourCatalogFeignClient tourCatalogFeignClient;
    private final BookingFeignClient bookingFeignClient;
    private final VectorService vectorService;
    private final VectorSyncService vectorSyncService;

    public VectorSyncService.SyncCounts syncEvents(List<ChatbotSyncEventDTO> events) {
        if (events == null || events.isEmpty()) return VectorSyncService.SyncCounts.empty();

        Set<Integer> tourIds = new LinkedHashSet<>();
        Set<Integer> locationIds = new LinkedHashSet<>();
        Set<Integer> reviewIds = new LinkedHashSet<>();
        Set<Integer> couponIds = new LinkedHashSet<>();
        Set<Integer> deletedTourIds = new LinkedHashSet<>();
        Set<Integer> deletedLocationIds = new LinkedHashSet<>();
        Set<Integer> deletedReviewIds = new LinkedHashSet<>();
        Set<Integer> deletedCouponIds = new LinkedHashSet<>();

        for (ChatbotSyncEventDTO event : events) {
            String type = normalize(event.getEntityType());
            boolean deleted = isDelete(event);
            Integer entityId = event.getEntityId();
            Integer parentTourId = event.getParentTourId();

            if (isTourChildType(type)) {
                Integer tourId = parentTourId != null ? parentTourId : entityId;
                if (tourId != null) {
                    if (deleted && "tour".equals(type)) deletedTourIds.add(tourId);
                    else tourIds.add(tourId);
                }
            } else if ("location".equals(type) && entityId != null) {
                if (deleted) deletedLocationIds.add(entityId);
                else locationIds.add(entityId);
            } else if ("review".equals(type) && entityId != null) {
                if (deleted) deletedReviewIds.add(entityId);
                else reviewIds.add(entityId);
            } else if ("coupon".equals(type) && entityId != null) {
                if (deleted) deletedCouponIds.add(entityId);
                else couponIds.add(entityId);
            }
        }

        deletedTourIds.forEach(this::deleteTourVectors);
        deletedLocationIds.forEach(id -> vectorService.deleteVectorsByFilter(Map.of("locationID", id)));
        deletedReviewIds.forEach(id -> vectorService.deleteVectorsByFilter(Map.of("reviewID", id)));
        deletedCouponIds.forEach(id -> vectorService.deleteVectorsByFilter(Map.of("couponID", id)));

        int tourDocs = 0;
        int locationDocs = 0;
        int reviewDocs = 0;
        int couponDocs = 0;

        for (Integer tourId : tourIds) {
            tourDocs += syncTourById(tourId);
        }
        for (Integer locationId : locationIds) {
            locationDocs += syncLocationById(locationId);
        }
        for (Integer reviewId : reviewIds) {
            reviewDocs += syncReviewById(reviewId);
        }
        for (Integer couponId : couponIds) {
            couponDocs += syncCouponById(couponId);
        }

        return new VectorSyncService.SyncCounts(tourDocs, locationDocs, reviewDocs, couponDocs);
    }

    public String describeEntityTypes(List<ChatbotSyncEventDTO> events) {
        if (events == null || events.isEmpty()) return "";
        return events.stream()
                .map(ChatbotSyncEventDTO::getEntityType)
                .filter(type -> type != null && !type.isBlank())
                .map(type -> type.toUpperCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.joining(","));
    }

    public int syncTourById(Integer tourId) {
        if (tourId == null) return 0;
        try {
            deleteTourVectors(tourId);
            TourSyncDTO tour = tourCatalogFeignClient.getTourForChatbotSync(tourId);
            return vectorSyncService.syncTour(tour);
        } catch (Exception e) {
            log.warn("Could not incremental sync tour {}: {}", tourId, e.getMessage());
            deleteTourVectors(tourId);
            return 0;
        }
    }

    public int syncLocationById(Integer locationId) {
        if (locationId == null) return 0;
        try {
            vectorService.deleteVectorsByFilter(Map.of("locationID", locationId));
            LocationSyncDTO location = tourCatalogFeignClient.getLocationForChatbotSync(locationId);
            return vectorSyncService.syncLocationDocument(location);
        } catch (Exception e) {
            log.warn("Could not incremental sync location {}: {}", locationId, e.getMessage());
            vectorService.deleteVectorsByFilter(Map.of("locationID", locationId));
            return 0;
        }
    }

    public int syncReviewById(Integer reviewId) {
        if (reviewId == null) return 0;
        try {
            vectorService.deleteVectorsByFilter(Map.of("reviewID", reviewId));
            ReviewSyncDTO review = tourCatalogFeignClient.getReviewForChatbotSync(reviewId);
            return vectorSyncService.syncReviewDocument(review);
        } catch (Exception e) {
            log.warn("Could not incremental sync review {}: {}", reviewId, e.getMessage());
            vectorService.deleteVectorsByFilter(Map.of("reviewID", reviewId));
            return 0;
        }
    }

    public int syncCouponById(Integer couponId) {
        if (couponId == null) return 0;
        try {
            vectorService.deleteVectorsByFilter(Map.of("couponID", couponId));
            CouponSyncDTO coupon = bookingFeignClient.getCouponForChatbotSync(couponId);
            return vectorSyncService.syncCouponDocument(coupon);
        } catch (Exception e) {
            log.warn("Could not incremental sync coupon {}: {}", couponId, e.getMessage());
            vectorService.deleteVectorsByFilter(Map.of("couponID", couponId));
            return 0;
        }
    }

    public void deleteTourVectors(Integer tourId) {
        if (tourId == null) return;
        vectorService.deleteVectorsByFilter(Map.of("tourId", tourId));
        vectorService.deleteVectorsByFilter(Map.of("tourID", tourId));
    }

    private boolean isTourChildType(String type) {
        return Set.of("tour", "departure", "itinerary", "itinerary_day", "meals", "hotel", "attractions")
                .contains(type);
    }

    private boolean isDelete(ChatbotSyncEventDTO event) {
        String operation = normalize(event.getOperation());
        return operation.contains("delete") || operation.contains("inactive") || operation.contains("hidden");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

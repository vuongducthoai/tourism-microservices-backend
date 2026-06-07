package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.VectorDocumentDTO;
import com.tourism.analytics.dto.feign.CouponSyncDTO;
import com.tourism.analytics.dto.feign.LocationSyncDTO;
import com.tourism.analytics.dto.feign.ReviewSyncDTO;
import com.tourism.analytics.dto.feign.TourSyncDTO;
import com.tourism.analytics.feign.BookingFeignClient;
import com.tourism.analytics.feign.TourCatalogFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * VectorSyncService — lấy dữ liệu từ tour-catalog-service (qua Feign)
 * và đồng bộ lên Pinecone Vector DB.
 *
 * Loại document được sync:
 *   - TOUR_SUMMARY   : thông tin tổng hợp mỗi tour
 *   - TOUR_DEPARTURE : thông tin từng departure (ngày khởi hành + giá)
 *   - LOCATION       : điểm đến du lịch
 *   - REVIEW         : đánh giá của khách hàng
 *   - COUPON         : mã giảm giá từ booking-service
 *
 * Chạy tự động lúc 2:00 AM mỗi ngày.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VectorSyncService {

    private final TourCatalogFeignClient tourCatalogFeignClient;
    private final BookingFeignClient     bookingFeignClient;
    private final VectorService          vectorService;
    private final Gson                   gson = new Gson();

    // ─────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────

    /**
     * Sync toàn bộ: tours + locations + reviews.
     * Được gọi từ admin endpoint hoặc scheduled job.
     */
    public void syncAll() {
        log.info("🔄 Starting full sync to Pinecone...");
        int tourDocs   = syncAllTours();
        int locDocs    = syncAllLocations();
        int revDocs    = syncAllReviews();
        int couponDocs = syncAllCoupons();
        log.info("✅ Sync completed: {} tour docs, {} location docs, {} review docs, {} coupon docs",
                tourDocs, locDocs, revDocs, couponDocs);
    }

    // ─────────────────────────────────────────────
    // TOURS
    // ─────────────────────────────────────────────

    /**
     * Sync toàn bộ tour active + departures từ tour-catalog.
     * @return tổng số document đã upsert
     */
    public int syncAllTours() {
        try {
            List<TourSyncDTO> tours = tourCatalogFeignClient.getAllToursForChatbotSync();
            log.info("📦 Fetched {} tours for sync", tours.size());
            int count = 0;
            for (TourSyncDTO tour : tours) {
                count += syncTourSummary(tour);
                count += syncTourAmenities(tour);
                count += syncTourItineraryDays(tour);
                count += syncTourStartLocation(tour);
                count += syncTourDepartures(tour);
            }
            log.info("✅ Tours synced: {} total documents", count);
            return count;
        } catch (Exception e) {
            log.error("❌ Error syncing tours: {}", e.getMessage(), e);
            return 0;
        }
    }

    // ─────────────────────────────────────────────
    // LOCATIONS
    // ─────────────────────────────────────────────

    /**
     * Sync danh sách điểm đến du lịch.
     * @return số document đã upsert
     */
    public int syncAllLocations() {
        try {
            List<LocationSyncDTO> locations = tourCatalogFeignClient.getLocationsForChatbotSync();
            log.info("📦 Fetched {} locations for sync", locations.size());
            int count = 0;
            for (LocationSyncDTO loc : locations) {
                count += syncLocation(loc);
            }
            log.info("✅ Locations synced: {} documents", count);
            return count;
        } catch (Exception e) {
            log.error("❌ Error syncing locations: {}", e.getMessage(), e);
            return 0;
        }
    }

    // ─────────────────────────────────────────────
    // REVIEWS
    // ─────────────────────────────────────────────

    /**
     * Sync tất cả review visible từ tour-catalog.
     * @return số document đã upsert
     */
    public int syncAllReviews() {
        try {
            List<ReviewSyncDTO> reviews = tourCatalogFeignClient.getAllVisibleReviews();
            log.info("📦 Fetched {} reviews for sync", reviews.size());
            int count = 0;
            for (ReviewSyncDTO review : reviews) {
                count += syncReview(review);
            }
            log.info("✅ Reviews synced: {} documents", count);
            return count;
        } catch (Exception e) {
            log.error("❌ Error syncing reviews: {}", e.getMessage(), e);
            return 0;
        }
    }

    // ─────────────────────────────────────────────
    // SCHEDULED
    // ─────────────────────────────────────────────

    /**
     * Tự động sync lúc 2:00 AM mỗi ngày.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void scheduledSync() {
        log.info("⏰ Scheduled sync triggered at 2:00 AM");
        syncAll();
    }

    // ─────────────────────────────────────────────
    // INTERNAL — TOUR SUMMARY
    // ─────────────────────────────────────────────

    private int syncTourSummary(TourSyncDTO tour) {
        try {
            String content = buildTourSummaryContent(tour);
            String docId   = "TOUR_SUMMARY_" + tour.getTourID();

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tourId",            tour.getTourID());
            meta.put("tourCode",          tour.getTourCode());
            meta.put("tourName",          tour.getTourName());
            meta.put("imageUrl",          nvl(tour.getImageUrl()));
            meta.put("duration",          nvl(tour.getDuration()));
            meta.put("startLocationName", nvl(tour.getStartLocationName()));
            meta.put("startLocationID",   tour.getStartLocationID());
            meta.put("endLocationName",   nvl(tour.getEndLocationName()));
            meta.put("endLocationID",     tour.getEndLocationID());
            meta.put("avgRating",         tour.getAvgRating() != null ? tour.getAvgRating() : 0.0);
            meta.put("reviewCount",       tour.getReviewCount() != null ? tour.getReviewCount() : 0);

            // Min price từ first upcoming departure
            double minPrice = 0;
            if (tour.getDepartures() != null && !tour.getDepartures().isEmpty()) {
                minPrice = tour.getDepartures().stream()
                        .filter(d -> d.getAdultSalePrice() != null)
                        .mapToDouble(TourSyncDTO.DepartureSyncDTO::getAdultSalePrice)
                        .min().orElse(0);
            }
            meta.put("minPrice", minPrice);

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("TOUR_SUMMARY")
                    .entityId(tour.getTourID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("❌ Error syncing tour summary {}: {}", tour.getTourCode(), e.getMessage());
            return 0;
        }
    }

    private String buildTourSummaryContent(TourSyncDTO tour) {
        StringBuilder sb = new StringBuilder();
        sb.append("Tour: ").append(tour.getTourName())
          .append(" | Code: ").append(tour.getTourCode())
          .append(" | Thời gian: ").append(nvl(tour.getDuration()))
          .append(" | Điểm khởi hành: ").append(nvl(tour.getStartLocationName()))
          .append(" | Điểm đến: ").append(nvl(tour.getEndLocationName()))
          .append(" | Phương tiện: ").append(nvl(tour.getTransportation()));

        if (hasText(tour.getAttractions()))
            sb.append(" | Điểm tham quan: ").append(tour.getAttractions());
        if (hasText(tour.getMeals()))
            sb.append(" | Bữa ăn: ").append(tour.getMeals());
        if (hasText(tour.getHotel()))
            sb.append(" | Khách sạn: ").append(tour.getHotel());

        if (tour.getAvgRating() != null && tour.getAvgRating() > 0)
            sb.append(" | Đánh giá trung bình: ").append(String.format("%.1f", tour.getAvgRating()))
              .append("/5 (").append(tour.getReviewCount()).append(" lượt đánh giá)");

        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // INTERNAL — TOUR DEPARTURES
    // ─────────────────────────────────────────────

    private int syncTourDepartures(TourSyncDTO tour) {
        if (tour.getDepartures() == null) return 0;
        int count = 0;
        for (TourSyncDTO.DepartureSyncDTO dep : tour.getDepartures()) {
            count += syncOneDeparture(tour, dep);
            count += syncOneDepartureFull(tour, dep);
        }
        return count;
    }

    private int syncOneDeparture(TourSyncDTO tour, TourSyncDTO.DepartureSyncDTO dep) {
        try {
            String content = buildDepartureContent(tour, dep);
            String docId   = "TOUR_DEPARTURE_" + dep.getDepartureID();

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("tourId",            tour.getTourID());
            meta.put("tourCode",          tour.getTourCode());
            meta.put("tourName",          tour.getTourName());
            meta.put("imageUrl",          nvl(tour.getImageUrl()));
            meta.put("duration",          nvl(tour.getDuration()));
            meta.put("startLocationName", nvl(tour.getStartLocationName()));
            meta.put("startLocationID",   tour.getStartLocationID());
            meta.put("endLocationName",   nvl(tour.getEndLocationName()));
            meta.put("endLocationID",     tour.getEndLocationID());
            meta.put("departureID",       dep.getDepartureID());
            meta.put("departureDate",     nvl(dep.getDepartureDate()));
            meta.put("availableSlots",    dep.getAvailableSlots() != null ? dep.getAvailableSlots() : 0);
            meta.put("salePrice",         dep.getAdultSalePrice()    != null ? dep.getAdultSalePrice()    : 0.0);
            meta.put("originalPrice",     dep.getAdultOriginalPrice() != null ? dep.getAdultOriginalPrice() : 0.0);

            // Coupon info if available
            if (dep.getCouponDiscount() != null && dep.getCouponDiscount() > 0) {
                meta.put("couponCode",      nvl(dep.getCouponCode()));
                meta.put("couponDiscount",  dep.getCouponDiscount());
                meta.put("couponStartDate", nvl(dep.getCouponStartDate()));
                meta.put("couponEndDate",   nvl(dep.getCouponEndDate()));
            }

            // Discount percentage
            if (dep.getAdultOriginalPrice() != null && dep.getAdultOriginalPrice() > 0
                    && dep.getAdultSalePrice() != null) {
                double pct = (1 - dep.getAdultSalePrice() / dep.getAdultOriginalPrice()) * 100;
                if (pct > 0) meta.put("discountPercentage", Math.round(pct));
            }

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("TOUR_DEPARTURE")
                    .entityId(dep.getDepartureID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("❌ Error syncing departure {}: {}", dep.getDepartureID(), e.getMessage());
            return 0;
        }
    }

    private String buildDepartureContent(TourSyncDTO tour, TourSyncDTO.DepartureSyncDTO dep) {
        StringBuilder sb = new StringBuilder();
        sb.append("Lịch khởi hành tour ").append(tour.getTourName())
          .append(" (").append(tour.getTourCode()).append(")")
          .append(" | Ngày: ").append(nvl(dep.getDepartureDate()))
          .append(" | Còn ").append(dep.getAvailableSlots()).append(" chỗ")
          .append(" | Giá người lớn: ").append(formatPrice(dep.getAdultSalePrice()));

        if (dep.getAdultOriginalPrice() != null && dep.getAdultOriginalPrice() > 0
                && dep.getAdultSalePrice() != null
                && dep.getAdultSalePrice() < dep.getAdultOriginalPrice()) {
            double pct = (1 - dep.getAdultSalePrice() / dep.getAdultOriginalPrice()) * 100;
            sb.append(" (giảm ").append(Math.round(pct)).append("% so với giá gốc ")
              .append(formatPrice(dep.getAdultOriginalPrice())).append(")");
        }

        if (dep.getCouponDiscount() != null && dep.getCouponDiscount() > 0) {
            sb.append(" | Coupon ").append(nvl(dep.getCouponCode()))
              .append(" giảm thêm ").append(formatPrice(dep.getCouponDiscount()));
        }

        sb.append(" | Từ ").append(nvl(tour.getStartLocationName()))
          .append(" đến ").append(nvl(tour.getEndLocationName()));

        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // INTERNAL — LOCATION
    // ─────────────────────────────────────────────

    // Additional tour fact documents. Existing TOUR_SUMMARY and TOUR_DEPARTURE stay unchanged.
    private int syncTourAmenities(TourSyncDTO tour) {
        try {
            if (!hasText(tour.getMeals()) && !hasText(tour.getHotel()) && !hasText(tour.getAttractions())) return 0;

            String content = buildAmenityContent(tour);
            String docId = "TOUR_AMENITY_" + tour.getTourID();

            Map<String, Object> meta = baseTourMeta(tour);
            meta.put("attractions", nvl(tour.getAttractions()));
            meta.put("meals", nvl(tour.getMeals()));
            meta.put("hotel", nvl(tour.getHotel()));
            meta.put("minPrice", findMinPrice(tour));

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("TOUR_AMENITY")
                    .entityId(tour.getTourID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("Error syncing tour amenity {}: {}", tour.getTourCode(), e.getMessage());
            return 0;
        }
    }

    private int syncTourItineraryDays(TourSyncDTO tour) {
        if (tour.getItineraryDays() == null) return 0;
        int count = 0;
        for (TourSyncDTO.ItineraryDaySyncDTO day : tour.getItineraryDays()) {
            if (day == null || (!hasText(day.getTitle()) && !hasText(day.getDetails()))) continue;
            try {
                String content = buildItineraryContent(tour, day);
                Integer entityId = day.getItineraryDayID() != null ? day.getItineraryDayID() : tour.getTourID();
                String docId = day.getItineraryDayID() != null
                        ? "TOUR_ITINERARY_DAY_" + day.getItineraryDayID()
                        : "TOUR_ITINERARY_DAY_" + tour.getTourID() + "_" + day.getDayNumber();

                Map<String, Object> meta = baseTourMeta(tour);
                meta.put("itineraryDayID", day.getItineraryDayID());
                meta.put("dayNumber", day.getDayNumber() != null ? day.getDayNumber() : 0);
                meta.put("title", nvl(day.getTitle()));
                meta.put("meals", nvl(day.getMeals()));

                List<Float> embedding = vectorService.createEmbedding(content);
                if (embedding.isEmpty()) continue;

                vectorService.upsertVector(VectorDocumentDTO.builder()
                        .id(docId)
                        .content(content)
                        .type("TOUR_ITINERARY_DAY")
                        .entityId(entityId)
                        .embedding(embedding)
                        .metadata(gson.toJson(meta))
                        .build());
                count++;
            } catch (Exception e) {
                log.error("Error syncing itinerary day {} for {}: {}", day.getDayNumber(), tour.getTourCode(), e.getMessage());
            }
        }
        return count;
    }

    private int syncTourStartLocation(TourSyncDTO tour) {
        try {
            if (!hasText(tour.getStartLocationName())) return 0;

            String content = buildStartLocationContent(tour);
            String docId = "TOUR_START_LOCATION_" + tour.getTourID();

            Map<String, Object> meta = baseTourMeta(tour);
            meta.put("minPrice", findMinPrice(tour));
            meta.put("departureDates", buildDepartureDates(tour));
            meta.put("startAliases", buildStartAliases(tour.getStartLocationName()));

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("TOUR_START_LOCATION")
                    .entityId(tour.getTourID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("Error syncing tour start location {}: {}", tour.getTourCode(), e.getMessage());
            return 0;
        }
    }

    private int syncOneDepartureFull(TourSyncDTO tour, TourSyncDTO.DepartureSyncDTO dep) {
        try {
            String content = buildDepartureFullContent(tour, dep);
            String docId = "TOUR_DEPARTURE_FULL_" + dep.getDepartureID();

            Map<String, Object> meta = baseTourMeta(tour);
            meta.put("departureID", dep.getDepartureID());
            meta.put("departureDate", nvl(dep.getDepartureDate()));
            meta.put("availableSlots", dep.getAvailableSlots() != null ? dep.getAvailableSlots() : 0);
            meta.put("salePrice", dep.getAdultSalePrice() != null ? dep.getAdultSalePrice() : 0.0);
            meta.put("originalPrice", dep.getAdultOriginalPrice() != null ? dep.getAdultOriginalPrice() : 0.0);
            meta.put("attractions", nvl(tour.getAttractions()));
            meta.put("meals", nvl(tour.getMeals()));
            meta.put("hotel", nvl(tour.getHotel()));
            meta.put("pricings", dep.getPricings() != null ? dep.getPricings() : Collections.emptyList());
            meta.put("transports", dep.getTransports() != null ? dep.getTransports() : Collections.emptyList());

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("TOUR_DEPARTURE_FULL")
                    .entityId(dep.getDepartureID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("Error syncing full departure {}: {}", dep.getDepartureID(), e.getMessage());
            return 0;
        }
    }

    private String buildAmenityContent(TourSyncDTO tour) {
        return new StringBuilder()
                .append("Thông tin tiện ích tour ").append(nvl(tour.getTourName()))
                .append(" (").append(nvl(tour.getTourCode())).append(")")
                .append(" | Thời gian: ").append(nvl(tour.getDuration()))
                .append(" | Khởi hành: ").append(nvl(tour.getStartLocationName()))
                .append(" | Điểm đến: ").append(nvl(tour.getEndLocationName()))
                .append(" | Điểm tham quan: ").append(nvl(tour.getAttractions()))
                .append(" | Ẩm thực/bữa ăn: ").append(nvl(tour.getMeals()))
                .append(" | Khách sạn/lưu trú: ").append(nvl(tour.getHotel()))
                .append(" | Phương tiện: ").append(nvl(tour.getTransportation()))
                .toString();
    }

    private String buildItineraryContent(TourSyncDTO tour, TourSyncDTO.ItineraryDaySyncDTO day) {
        return new StringBuilder()
                .append("Lịch trình tour ").append(nvl(tour.getTourName()))
                .append(" (").append(nvl(tour.getTourCode())).append(")")
                .append(" | Ngày ").append(day.getDayNumber() != null ? day.getDayNumber() : "")
                .append(": ").append(nvl(day.getTitle()))
                .append(" | Bữa ăn: ").append(nvl(day.getMeals()))
                .append(" | Chi tiết: ").append(stripHtml(day.getDetails()))
                .toString();
    }

    private String buildStartLocationContent(TourSyncDTO tour) {
        return new StringBuilder()
                .append("Tour khởi hành từ ").append(nvl(tour.getStartLocationName()))
                .append(": ").append(nvl(tour.getTourName()))
                .append(" (").append(nvl(tour.getTourCode())).append(")")
                .append(" | Điểm đến: ").append(nvl(tour.getEndLocationName()))
                .append(" | Thời gian: ").append(nvl(tour.getDuration()))
                .append(" | Ngày khởi hành: ").append(String.join(", ", buildDepartureDates(tour)))
                .append(" | Giá từ: ").append(formatPrice(findMinPrice(tour)))
                .append(" | Ẩm thực: ").append(nvl(tour.getMeals()))
                .append(" | Khách sạn: ").append(nvl(tour.getHotel()))
                .toString();
    }

    private String buildDepartureFullContent(TourSyncDTO tour, TourSyncDTO.DepartureSyncDTO dep) {
        return new StringBuilder()
                .append(buildDepartureContent(tour, dep))
                .append(" | Thông tin đầy đủ")
                .append(" | Giá theo hành khách: ").append(formatPricingList(dep.getPricings()))
                .append(" | Vận chuyển: ").append(formatTransportList(dep.getTransports()))
                .append(" | Ẩm thực: ").append(nvl(tour.getMeals()))
                .append(" | Khách sạn: ").append(nvl(tour.getHotel()))
                .append(" | Điểm tham quan: ").append(nvl(tour.getAttractions()))
                .toString();
    }

    private int syncLocation(LocationSyncDTO loc) {
        try {
            String content = buildLocationContent(loc);
            String docId   = "LOCATION_" + loc.getLocationID();

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("locationID",  loc.getLocationID());
            meta.put("name",        nvl(loc.getName()));
            meta.put("region",      nvl(loc.getRegion()));
            meta.put("imageUrl",    nvl(loc.getImageUrl()));
            meta.put("airportCode", nvl(loc.getAirportCode()));
            meta.put("airportName", nvl(loc.getAirportName()));

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("LOCATION")
                    .entityId(loc.getLocationID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("❌ Error syncing location {}: {}", loc.getName(), e.getMessage());
            return 0;
        }
    }

    private String buildLocationContent(LocationSyncDTO loc) {
        StringBuilder sb = new StringBuilder();
        sb.append("Điểm đến du lịch: ").append(nvl(loc.getName()));
        if (hasText(loc.getRegion()))   sb.append(" | Khu vực: ").append(loc.getRegion());
        if (hasText(loc.getDescription())) sb.append(" | ").append(loc.getDescription());
        if (hasText(loc.getAirportName()))
            sb.append(" | Sân bay: ").append(loc.getAirportName())
              .append(" (").append(loc.getAirportCode()).append(")");
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // INTERNAL — REVIEW
    // ─────────────────────────────────────────────

    private int syncReview(ReviewSyncDTO review) {
        try {
            if (!hasText(review.getComment())) return 0;

            String content = buildReviewContent(review);
            String docId   = "REVIEW_" + review.getReviewID();

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("reviewID", review.getReviewID());
            meta.put("tourID",   review.getTourID());
            meta.put("tourCode", nvl(review.getTourCode()));
            meta.put("tourName", nvl(review.getTourName()));
            meta.put("rating",   review.getRating());

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("REVIEW")
                    .entityId(review.getReviewID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("❌ Error syncing review {}: {}", review.getReviewID(), e.getMessage());
            return 0;
        }
    }

    private String buildReviewContent(ReviewSyncDTO review) {
        return "Đánh giá tour " + nvl(review.getTourName())
                + " (" + nvl(review.getTourCode()) + ")"
                + " | Điểm: " + review.getRating() + "/5"
                + " | Nhận xét: " + review.getComment();
    }

    // ─────────────────────────────────────────────
    // INTERNAL — COUPONS
    // ─────────────────────────────────────────────

    /**
     * Sync tất cả coupon active từ booking-service.
     * @return số document đã upsert
     */
    public int syncAllCoupons() {
        try {
            List<CouponSyncDTO> coupons = bookingFeignClient.getCouponsForChatbotSync();
            log.info("📦 Fetched {} coupons for sync", coupons.size());
            int count = 0;
            for (CouponSyncDTO coupon : coupons) {
                count += syncCoupon(coupon);
            }
            log.info("✅ Coupons synced: {} documents", count);
            return count;
        } catch (Exception e) {
            log.error("❌ Error syncing coupons: {}", e.getMessage(), e);
            return 0;
        }
    }

    private int syncCoupon(CouponSyncDTO coupon) {
        try {
            String content = buildCouponContent(coupon);
            String docId   = "COUPON_" + coupon.getCouponID();

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("couponID",      coupon.getCouponID());
            meta.put("couponCode",    nvl(coupon.getCouponCode()));
            meta.put("description",   nvl(coupon.getDescription()));
            meta.put("discountAmount", coupon.getDiscountAmount() != null ? coupon.getDiscountAmount() : 0);
            meta.put("startDate",     nvl(coupon.getStartDate()));
            meta.put("endDate",       nvl(coupon.getEndDate()));
            meta.put("usageLimit",    coupon.getUsageLimit() != null ? coupon.getUsageLimit() : 0);
            meta.put("usageCount",    coupon.getUsageCount() != null ? coupon.getUsageCount() : 0);
            meta.put("couponType",    nvl(coupon.getCouponType()));
            if (coupon.getDepartureId() != null) {
                meta.put("departureId", coupon.getDepartureId());
            }

            List<Float> embedding = vectorService.createEmbedding(content);
            if (embedding.isEmpty()) return 0;

            vectorService.upsertVector(VectorDocumentDTO.builder()
                    .id(docId)
                    .content(content)
                    .type("COUPON")
                    .entityId(coupon.getCouponID())
                    .embedding(embedding)
                    .metadata(gson.toJson(meta))
                    .build());
            return 1;
        } catch (Exception e) {
            log.error("❌ Error syncing coupon {}: {}", coupon.getCouponCode(), e.getMessage());
            return 0;
        }
    }

    private String buildCouponContent(CouponSyncDTO coupon) {
        StringBuilder sb = new StringBuilder();
        sb.append("Mã giảm giá: ").append(nvl(coupon.getCouponCode()))
          .append(" | Giảm: ").append(String.format("%,d", coupon.getDiscountAmount() != null ? coupon.getDiscountAmount() : 0)).append(" VND");
        if ("DEPARTURE".equals(coupon.getCouponType()) && coupon.getDepartureId() != null) {
            sb.append(" | Loại: Áp dụng riêng cho lịch khởi hành ID=").append(coupon.getDepartureId());
        } else {
            sb.append(" | Loại: Áp dụng cho tất cả tour (toàn hệ thống)");
        }
        if (hasText(coupon.getDescription())) {
            sb.append(" | Mô tả: ").append(coupon.getDescription());
        }
        if (hasText(coupon.getEndDate())) {
            sb.append(" | Hạn sử dụng: ").append(coupon.getEndDate().substring(0, 10));
        }
        if (coupon.getUsageLimit() != null && coupon.getUsageLimit() > 0) {
            int remaining = coupon.getUsageLimit() - (coupon.getUsageCount() != null ? coupon.getUsageCount() : 0);
            sb.append(" | Còn lại: ").append(remaining).append(" lượt");
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────
    // UTILS
    // ─────────────────────────────────────────────

    private Map<String, Object> baseTourMeta(TourSyncDTO tour) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tourId", tour.getTourID());
        meta.put("tourCode", nvl(tour.getTourCode()));
        meta.put("tourName", nvl(tour.getTourName()));
        meta.put("imageUrl", nvl(tour.getImageUrl()));
        meta.put("duration", nvl(tour.getDuration()));
        meta.put("startLocationName", nvl(tour.getStartLocationName()));
        meta.put("startLocationID", tour.getStartLocationID());
        meta.put("endLocationName", nvl(tour.getEndLocationName()));
        meta.put("endLocationID", tour.getEndLocationID());
        return meta;
    }

    private double findMinPrice(TourSyncDTO tour) {
        if (tour.getDepartures() == null) return 0.0;
        return tour.getDepartures().stream()
                .filter(d -> d.getAdultSalePrice() != null)
                .mapToDouble(TourSyncDTO.DepartureSyncDTO::getAdultSalePrice)
                .min()
                .orElse(0.0);
    }

    private List<String> buildDepartureDates(TourSyncDTO tour) {
        if (tour.getDepartures() == null) return Collections.emptyList();
        List<String> dates = new ArrayList<>();
        for (TourSyncDTO.DepartureSyncDTO dep : tour.getDepartures()) {
            if (hasText(dep.getDepartureDate())) dates.add(dep.getDepartureDate());
        }
        return dates;
    }

    private List<String> buildStartAliases(String startLocationName) {
        List<String> aliases = new ArrayList<>();
        if (hasText(startLocationName)) {
            aliases.add(startLocationName);
            String normalized = startLocationName.toLowerCase(Locale.ROOT);
            if (normalized.contains("hồ chí minh") || normalized.contains("ho chi minh")) {
                aliases.add("HCM");
                aliases.add("Sài Gòn");
                aliases.add("Sai Gon");
                aliases.add("TP Hồ Chí Minh");
            }
        }
        return aliases;
    }

    private String formatPricingList(List<TourSyncDTO.PricingSyncDTO> pricings) {
        if (pricings == null || pricings.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (TourSyncDTO.PricingSyncDTO pricing : pricings) {
            lines.add(nvl(pricing.getPassengerType())
                    + " " + nvl(pricing.getAgeDescription())
                    + ": " + formatPrice(pricing.getSalePrice()));
        }
        return String.join("; ", lines);
    }

    private String formatTransportList(List<TourSyncDTO.TransportSyncDTO> transports) {
        if (transports == null || transports.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (TourSyncDTO.TransportSyncDTO transport : transports) {
            lines.add(nvl(transport.getTransportType())
                    + " " + nvl(transport.getVehicleType())
                    + " " + nvl(transport.getVehicleName())
                    + " " + nvl(transport.getStartPoint())
                    + " -> " + nvl(transport.getEndPoint())
                    + " " + nvl(transport.getDepartTime())
                    + " - " + nvl(transport.getArrivalTime()));
        }
        return String.join("; ", lines);
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replace("&nbsp;", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String nvl(String s) {
        return s != null ? s : "";
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private String formatPrice(Double price) {
        if (price == null) return "N/A";
        return String.format("%,.0f VNĐ", price);
    }
}

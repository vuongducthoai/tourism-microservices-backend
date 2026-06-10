package com.tourism.booking.service.impl;

import com.tourism.booking.dto.request.CouponRequest;
import com.tourism.booking.dto.response.AdminCouponResponse;
import com.tourism.booking.entity.Coupon;
import com.tourism.booking.entity.CouponType;
import com.tourism.booking.event.CouponEventDTO;
import com.tourism.booking.feign.NotificationFeignClient;
import com.tourism.booking.feign.TourCatalogFeignClient;
import com.tourism.booking.feign.dto.DepartureInfoResponse;
import com.tourism.booking.repository.CouponRepository;
import com.tourism.booking.service.AdminCouponService;
import com.tourism.booking.service.ChatbotSyncEventPublisher;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCouponServiceImpl implements AdminCouponService {

    private final CouponRepository couponRepository;
    private final TourCatalogFeignClient tourCatalogClient;
    private final NotificationFeignClient notificationClient;
    private final ChatbotSyncEventPublisher chatbotSyncEventPublisher;

    @Override
    @Transactional
    public AdminCouponResponse createCoupon(CouponRequest request) {
        if (couponRepository.existsByCouponCode(request.getCouponCode())) {
            throw new IllegalArgumentException("Mã coupon '" + request.getCouponCode() + "' đã tồn tại");
        }

        Coupon coupon = new Coupon();
        applyRequest(coupon, request);
        Coupon saved = couponRepository.save(coupon);

        if (Boolean.TRUE.equals(request.getSendNotification())) {
            sendCouponCreatedNotification(saved, request);
        }
        chatbotSyncEventPublisher.publish("coupon", saved.getCouponID(), "CREATE");

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public AdminCouponResponse updateCoupon(Integer id, CouponRequest request) {
        Coupon coupon = findActiveById(id);

        if (!coupon.getCouponCode().equals(request.getCouponCode()) &&
                couponRepository.existsByCouponCodeAndNotId(request.getCouponCode(), id)) {
            throw new IllegalArgumentException("Mã coupon '" + request.getCouponCode() + "' đã tồn tại");
        }

        applyRequest(coupon, request);
        Coupon saved = couponRepository.save(coupon);

        if (Boolean.TRUE.equals(request.getSendNotification())) {
            sendCouponUpdatedNotification(saved);
        }
        chatbotSyncEventPublisher.publish("coupon", saved.getCouponID(), "UPDATE");

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public void deleteCoupon(Integer id) {
        Coupon coupon = findActiveById(id);
        coupon.setIsDeleted(true);
        couponRepository.save(coupon);
        chatbotSyncEventPublisher.publish("coupon", coupon.getCouponID(), "DELETE");
    }

    @Override
    @Transactional(readOnly = true)
    public AdminCouponResponse getCouponById(Integer id) {
        return mapToResponse(findActiveById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminCouponResponse> getAllCoupons(Pageable pageable) {
        return couponRepository.findAllSorted(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminCouponResponse> getGlobalCoupons(Pageable pageable) {
        return couponRepository.findGlobalCoupons(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminCouponResponse> getDepartureCoupons(Pageable pageable) {
        return couponRepository.findDepartureCoupons(pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminCouponResponse> searchCoupons(String keyword, Pageable pageable) {
        return couponRepository.searchCoupons(keyword, pageable).map(this::mapToResponse);
    }

    private void applyRequest(Coupon coupon, CouponRequest request) {
        coupon.setCouponCode(request.getCouponCode());
        coupon.setDescription(request.getDescription());
        coupon.setDiscountAmount(request.getDiscountAmount());
        coupon.setStartDate(request.getStartDate());
        coupon.setEndDate(request.getEndDate());
        coupon.setUsageLimit(request.getUsageLimit());
        coupon.setMinOrderValue(request.getMinOrderValue());
        coupon.setCouponType(request.getCouponType());

        if (request.getCouponType() == CouponType.DEPARTURE &&
                request.getDepartureIds() != null && !request.getDepartureIds().isEmpty()) {
            coupon.setDepartureId(request.getDepartureIds().get(0));
        } else if (request.getCouponType() == CouponType.GLOBAL) {
            coupon.setDepartureId(null);
        }
    }

    private Coupon findActiveById(Integer id) {
        return couponRepository.findById(id)
                .filter(c -> !Boolean.TRUE.equals(c.getIsDeleted()))
                .orElseThrow(() -> new EntityNotFoundException("Không tìm thấy coupon với ID: " + id));
    }

    private AdminCouponResponse mapToResponse(Coupon c) {
        List<AdminCouponResponse.DepartureInfo> departureDetails = Collections.emptyList();

        if (c.getCouponType() == CouponType.DEPARTURE && c.getDepartureId() != null) {
            try {
                DepartureInfoResponse dep = tourCatalogClient.getDepartureInfo(c.getDepartureId());
                if (dep != null) {
                    departureDetails = List.of(AdminCouponResponse.DepartureInfo.builder()
                            .departureId(dep.getDepartureID() != null ? dep.getDepartureID() : c.getDepartureId())
                            .tourCode(dep.getTourCode())
                            .tourName(dep.getTourName())
                            .tourId(dep.getTourID())
                            .departureDate(dep.getDepartureDate())
                            .build());
                }
            } catch (Exception e) {
                log.warn("Failed to fetch departure info for departureId={}: {}", c.getDepartureId(), e.getMessage());
            }
        }

        boolean isActive = !Boolean.TRUE.equals(c.getIsDeleted()) &&
                (c.getEndDate() == null || c.getEndDate().isAfter(java.time.LocalDateTime.now()));

        return AdminCouponResponse.builder()
                .couponId(c.getCouponID())
                .couponCode(c.getCouponCode())
                .description(c.getDescription())
                .discountAmount(c.getDiscountAmount())
                .startDate(c.getStartDate())
                .endDate(c.getEndDate())
                .usageLimit(c.getUsageLimit())
                .usageCount(c.getUsageCount())
                .minOrderValue(c.getMinOrderValue())
                .isActive(isActive)
                .couponType(c.getCouponType() != null ? c.getCouponType().name() : null)
                .departureDetails(departureDetails)
                .createdAt(c.getCreatedAt())
                .updatedAt(c.getUpdatedAt())
                .build();
    }

    private void sendCouponCreatedNotification(Coupon coupon, CouponRequest request) {
        try {
            String departureDate = null;
            String tourName = null;
            if (coupon.getCouponType() == CouponType.DEPARTURE && coupon.getDepartureId() != null) {
                try {
                    DepartureInfoResponse dep = tourCatalogClient.getDepartureInfo(coupon.getDepartureId());
                    if (dep != null) {
                        departureDate = dep.getDepartureDate();
                        tourName = dep.getTourName();
                    }
                } catch (Exception ignored) {}
            }

            CouponEventDTO event = CouponEventDTO.builder()
                    .couponId(coupon.getCouponID())
                    .couponCode(coupon.getCouponCode())
                    .discountAmount(coupon.getDiscountAmount())
                    .couponType(coupon.getCouponType().name())
                    .startDate(coupon.getStartDate() != null ? coupon.getStartDate().toString() : null)
                    .endDate(coupon.getEndDate() != null ? coupon.getEndDate().toString() : null)
                    .description(coupon.getDescription())
                    .eventType("COUPON_CREATED")
                    .tourName(tourName)
                    .departureDate(departureDate)
                    .build();

            notificationClient.notifyCouponCreated(event);
        } catch (Exception e) {
            log.warn("Coupon created notification failed: {}", e.getMessage());
        }
    }

    private void sendCouponUpdatedNotification(Coupon coupon) {
        try {
            CouponEventDTO event = CouponEventDTO.builder()
                    .couponId(coupon.getCouponID())
                    .couponCode(coupon.getCouponCode())
                    .discountAmount(coupon.getDiscountAmount())
                    .couponType(coupon.getCouponType().name())
                    .startDate(coupon.getStartDate() != null ? coupon.getStartDate().toString() : null)
                    .endDate(coupon.getEndDate() != null ? coupon.getEndDate().toString() : null)
                    .description(coupon.getDescription())
                    .eventType("COUPON_UPDATED")
                    .build();

            notificationClient.notifyCouponUpdated(event);
        } catch (Exception e) {
            log.warn("Coupon updated notification failed: {}", e.getMessage());
        }
    }
}

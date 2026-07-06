package com.tourism.booking.controller;

import com.tourism.booking.dto.response.CouponBriefResponse;
import com.tourism.booking.entity.Coupon;
import com.tourism.booking.entity.CouponType;
import com.tourism.booking.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponRepository couponRepository;

    @GetMapping("/departure/{couponId}")
    public ResponseEntity<CouponBriefResponse> getCouponById(@PathVariable Integer couponId) {
        Optional<Coupon> coupon = couponRepository.findActiveCouponById(couponId, LocalDateTime.now());
        return coupon
            .map(c -> ResponseEntity.ok(toResponse(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Coupon DEPARTURE tốt nhất (giảm nhiều nhất) đang áp cho 1 lịch — tour-catalog gọi để hiển thị/đặt tour. */
    @GetMapping("/best-for-departure")
    public ResponseEntity<CouponBriefResponse> getBestCouponForDeparture(@RequestParam Integer departureId) {
        List<Coupon> list = couponRepository.findActiveDepartureCoupons(departureId, LocalDateTime.now());
        if (list.isEmpty()) return ResponseEntity.notFound().build();
        CouponBriefResponse r = toResponse(list.get(0));
        r.setDepartureId(departureId);
        return ResponseEntity.ok(r);
    }

    /** Coupon tốt nhất cho NHIỀU lịch cùng lúc — tránh gọi N lần khi hiển thị trang chi tiết tour. */
    @GetMapping("/best-for-departures")
    public ResponseEntity<List<CouponBriefResponse>> getBestCouponsForDepartures(
            @RequestParam List<Integer> departureIds) {
        LocalDateTime now = LocalDateTime.now();
        List<CouponBriefResponse> result = new ArrayList<>();
        if (departureIds != null) {
            for (Integer depId : departureIds) {
                List<Coupon> list = couponRepository.findActiveDepartureCoupons(depId, now);
                if (!list.isEmpty()) {
                    CouponBriefResponse r = toResponse(list.get(0));
                    r.setDepartureId(depId);
                    result.add(r);
                }
            }
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/global")
    public ResponseEntity<CouponBriefResponse> getBestGlobalCoupon(
            @RequestParam BigDecimal orderValue) {
        Optional<Coupon> coupon = couponRepository.findBestGlobalCoupon(
            LocalDateTime.now(), orderValue, CouponType.GLOBAL
        );
        return coupon
            .map(c -> ResponseEntity.ok(toResponse(c)))
            .orElse(ResponseEntity.notFound().build());
    }

    private CouponBriefResponse toResponse(Coupon c) {
        return new CouponBriefResponse(
            c.getCouponID(),
            c.getCouponCode(),
            c.getDiscountAmount(),
            c.getCouponType() != null ? c.getCouponType().name() : null,
            c.getDepartureId()
        );
    }
}

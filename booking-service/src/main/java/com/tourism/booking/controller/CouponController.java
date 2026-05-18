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

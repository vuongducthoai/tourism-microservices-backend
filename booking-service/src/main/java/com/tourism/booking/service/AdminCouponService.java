package com.tourism.booking.service;

import com.tourism.booking.dto.request.CouponRequest;
import com.tourism.booking.dto.response.AdminCouponResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminCouponService {

    AdminCouponResponse createCoupon(CouponRequest request);

    AdminCouponResponse updateCoupon(Integer id, CouponRequest request);

    void deleteCoupon(Integer id);

    AdminCouponResponse getCouponById(Integer id);

    Page<AdminCouponResponse> getAllCoupons(Pageable pageable);

    Page<AdminCouponResponse> getGlobalCoupons(Pageable pageable);

    Page<AdminCouponResponse> getDepartureCoupons(Pageable pageable);

    Page<AdminCouponResponse> searchCoupons(String keyword, Pageable pageable);
}

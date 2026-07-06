package com.tourism.booking.repository;

import com.tourism.booking.entity.Coupon;
import com.tourism.booking.entity.CouponType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Integer> {

    boolean existsByCouponCode(String couponCode);

    @Query("SELECT COUNT(c) > 0 FROM Coupon c WHERE c.couponCode = :code AND c.couponID != :excludeId")
    boolean existsByCouponCodeAndNotId(@Param("code") String code, @Param("excludeId") Integer excludeId);

    @Query("SELECT c FROM Coupon c WHERE (c.isDeleted IS NULL OR c.isDeleted = false) ORDER BY c.createdAt DESC")
    Page<Coupon> findAllSorted(Pageable pageable);

    @Query("SELECT c FROM Coupon c WHERE c.couponType = 'GLOBAL' AND (c.isDeleted IS NULL OR c.isDeleted = false) ORDER BY c.createdAt DESC")
    Page<Coupon> findGlobalCoupons(Pageable pageable);

    @Query("SELECT c FROM Coupon c WHERE c.couponType = 'DEPARTURE' AND (c.isDeleted IS NULL OR c.isDeleted = false) ORDER BY c.createdAt DESC")
    Page<Coupon> findDepartureCoupons(Pageable pageable);

    @Query("SELECT c FROM Coupon c WHERE (c.isDeleted IS NULL OR c.isDeleted = false) AND " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           " LOWER(c.couponCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(c.description) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY c.createdAt DESC")
    Page<Coupon> searchCoupons(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT c FROM Coupon c WHERE (c.isDeleted IS NULL OR c.isDeleted = false) " +
           "AND (c.endDate IS NULL OR c.endDate > :now)")
    List<Coupon> findActiveCoupons(@Param("now") LocalDateTime now);

    Optional<Coupon> findByCouponCode(String couponCode);

    @Query("""
        SELECT c FROM Coupon c
        WHERE c.couponID = :couponId
        AND (c.isDeleted IS NULL OR c.isDeleted = false)
        AND (c.endDate IS NULL OR c.endDate > :now)
        AND (c.usageLimit IS NULL OR c.usageCount < c.usageLimit)
    """)
    Optional<Coupon> findActiveCouponById(
        @Param("couponId") Integer couponId,
        @Param("now") LocalDateTime now
    );

    /**
     * Các coupon DEPARTURE còn hiệu lực áp dụng cho 1 lịch khởi hành, sắp theo mức giảm giảm dần.
     * Hỗ trợ cả dữ liệu mới (departureIds) lẫn coupon cũ (departureId đơn).
     * Phần tử đầu tiên = coupon "tốt nhất" (giảm nhiều nhất).
     */
    @Query("""
        SELECT DISTINCT c FROM Coupon c
        LEFT JOIN c.departureIds d
        WHERE c.couponType = 'DEPARTURE'
          AND (d = :departureId OR c.departureId = :departureId)
          AND (c.isDeleted IS NULL OR c.isDeleted = false)
          AND (c.startDate IS NULL OR c.startDate <= :now)
          AND (c.endDate IS NULL OR c.endDate > :now)
          AND (c.usageLimit IS NULL OR c.usageCount < c.usageLimit)
        ORDER BY c.discountAmount DESC
    """)
    List<Coupon> findActiveDepartureCoupons(@Param("departureId") Integer departureId,
                                            @Param("now") LocalDateTime now);

    @Query("""
        SELECT c FROM Coupon c
        WHERE c.couponType = :globalType
        AND c.departureId IS NULL
        AND (c.isDeleted IS NULL OR c.isDeleted = false)
        AND (c.startDate IS NULL OR c.startDate <= :now)
        AND (c.endDate IS NULL OR c.endDate > :now)
        AND (c.usageLimit IS NULL OR c.usageCount < c.usageLimit)
        AND (c.minOrderValue IS NULL OR c.minOrderValue <= :orderValue)
        ORDER BY c.discountAmount DESC
        LIMIT 1
    """)
    Optional<Coupon> findBestGlobalCoupon(
        @Param("now") LocalDateTime now,
        @Param("orderValue") BigDecimal orderValue,
        @Param("globalType") CouponType globalType
    );
}

package com.tourism.booking.repository;

import com.tourism.booking.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface CouponRepository extends JpaRepository<Coupon, Integer> {

    /**
     * Lấy tất cả coupon chưa bị xóa và chưa hết hạn (hoặc không có hạn).
     * Dùng cho chatbot-sync để Pinecone biết coupon nào đang có hiệu lực.
     */
    @Query("SELECT c FROM Coupon c WHERE (c.isDeleted IS NULL OR c.isDeleted = false) " +
           "AND (c.endDate IS NULL OR c.endDate > :now)")
    List<Coupon> findActiveCoupons(@Param("now") LocalDateTime now);
}

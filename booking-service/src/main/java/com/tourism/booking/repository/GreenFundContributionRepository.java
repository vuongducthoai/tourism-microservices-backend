package com.tourism.booking.repository;

import com.tourism.booking.entity.GreenFundContribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface GreenFundContributionRepository extends JpaRepository<GreenFundContribution, Long> {

    boolean existsByOperationKey(String operationKey);

    /** Tổng đóng góp (VND) của 1 user — tính số cây cá nhân + badge. */
    @Query("SELECT COALESCE(SUM(c.amountVnd), 0) FROM GreenFundContribution c WHERE c.userId = :userId")
    BigDecimal sumAmountByUser(@Param("userId") Integer userId);

    long countByUserId(Integer userId);

    /** Số người đóng góp (distinct, không tính ẩn danh nguồn booking null user). */
    @Query("SELECT COUNT(DISTINCT c.userId) FROM GreenFundContribution c WHERE c.userId IS NOT NULL")
    long countDistinctContributors();

    /** Tổng quỹ theo nguồn (phân bổ booking vs donation). */
    @Query("SELECT c.source, COALESCE(SUM(c.amountVnd), 0) FROM GreenFundContribution c GROUP BY c.source")
    List<Object[]> sumBySource();

    List<GreenFundContribution> findTop10ByUserIdOrderByCreatedAtDesc(Integer userId);

    /** Lịch sử đóng góp gần đây (công khai — FE tự ẩn tên nếu anonymous). */
    List<GreenFundContribution> findTop10ByOrderByCreatedAtDesc();

    /** Bảng vinh danh: tổng góp theo user (loại ẩn danh), từ thời điểm :since (null = mọi lúc). */
    @Query(value = "SELECT user_id, COALESCE(SUM(amount_vnd), 0) AS total, COUNT(*) AS cnt " +
            "FROM green_fund_contributions " +
            "WHERE user_id IS NOT NULL AND anonymous = false " +
            "AND (CAST(:since AS timestamp) IS NULL OR created_at >= :since) " +
            "GROUP BY user_id ORDER BY total DESC LIMIT :limit", nativeQuery = true)
    List<Object[]> leaderboard(@Param("since") java.time.LocalDateTime since, @Param("limit") int limit);

    /** Admin audit: lọc theo nguồn / user, phân trang. */
    org.springframework.data.domain.Page<GreenFundContribution> findBySourceOrderByCreatedAtDesc(
            GreenFundContribution.Source source, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<GreenFundContribution> findByUserIdOrderByCreatedAtDesc(
            Integer userId, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<GreenFundContribution> findAllByOrderByCreatedAtDesc(
            org.springframework.data.domain.Pageable pageable);
}

package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumCoinRewardLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface ForumCoinRewardLogRepository extends JpaRepository<ForumCoinRewardLog, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<ForumCoinRewardLog> {

    boolean existsByOperationKey(String operationKey);

    /** Tổng coin đã thưởng (loại trừ status truyền vào) từ thời điểm start. */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM ForumCoinRewardLog l " +
           "WHERE l.userId = :userId AND l.createdAt >= :start AND l.status <> :excluded")
    BigDecimal sumAmountSinceExcluding(@Param("userId") Integer userId,
                                       @Param("start") LocalDateTime start,
                                       @Param("excluded") ForumCoinRewardLog.RewardStatus excluded);

    /** Tổng coin đã thưởng (không tính CANCELLED) từ thời điểm start — dùng cho trần ngày. */
    default BigDecimal sumAmountSince(Integer userId, LocalDateTime start) {
        return sumAmountSinceExcluding(userId, start, ForumCoinRewardLog.RewardStatus.CANCELLED);
    }

    /** Tổng coin theo status (mọi thời điểm). */
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM ForumCoinRewardLog l " +
           "WHERE l.userId = :userId AND l.status = :status")
    BigDecimal sumByStatus(@Param("userId") Integer userId,
                           @Param("status") ForumCoinRewardLog.RewardStatus status);

    /** Tổng coin đã thưởng thành công từ forum (mọi thời điểm). */
    default BigDecimal sumTotalCredited(Integer userId) {
        return sumByStatus(userId, ForumCoinRewardLog.RewardStatus.CREDITED);
    }

    /** Số lần được thưởng theo loại action từ thời điểm start — dùng cho quota/ngày. */
    long countByUserIdAndActionAndCreatedAtGreaterThanEqual(
            Integer userId, ForumCoinRewardLog.RewardAction action, LocalDateTime start);

    List<ForumCoinRewardLog> findTop10ByUserIdOrderByCreatedAtDesc(Integer userId);

    /** Toàn bộ lịch sử thưởng của user, mới nhất trước — có phân trang. */
    Page<ForumCoinRewardLog> findByUserIdOrderByCreatedAtDesc(Integer userId, Pageable pageable);

    /** Bản ghi PENDING (publish sang IAM thất bại) — scheduler retry. */
    List<ForumCoinRewardLog> findByStatusAndCreatedAtBefore(
            ForumCoinRewardLog.RewardStatus status, LocalDateTime before, Pageable pageable);

    // ════════════════ Admin: thống kê & đối soát ════════════════

    /** Tổng coin phát ra (toàn hệ thống, không tính CANCELLED) từ thời điểm start. */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM forum_coin_reward_logs " +
            "WHERE created_at >= :start AND status <> 'CANCELLED'", nativeQuery = true)
    BigDecimal sumAllSince(@Param("start") LocalDateTime start);

    /** Số user được thưởng từ thời điểm start. */
    @Query(value = "SELECT COUNT(DISTINCT user_id) FROM forum_coin_reward_logs " +
            "WHERE created_at >= :start AND status <> 'CANCELLED'", nativeQuery = true)
    long countDistinctUsersSince(@Param("start") LocalDateTime start);

    long countByStatus(ForumCoinRewardLog.RewardStatus status);

    /** Coin phát theo ngày (cho biểu đồ). */
    @Query(value = "SELECT CAST(created_at AS date) AS d, COALESCE(SUM(amount), 0) AS total, COUNT(*) AS cnt " +
            "FROM forum_coin_reward_logs WHERE created_at >= :start AND status <> 'CANCELLED' " +
            "GROUP BY CAST(created_at AS date) ORDER BY d", nativeQuery = true)
    List<Object[]> sumByDaySince(@Param("start") LocalDateTime start);

    /** Phân bổ theo loại action. */
    @Query(value = "SELECT action, COALESCE(SUM(amount), 0) AS total, COUNT(*) AS cnt " +
            "FROM forum_coin_reward_logs WHERE created_at >= :start AND status <> 'CANCELLED' " +
            "GROUP BY action ORDER BY total DESC", nativeQuery = true)
    List<Object[]> sumByActionSince(@Param("start") LocalDateTime start);

    /** Top user nhận nhiều coin nhất. */
    @Query(value = "SELECT user_id, COALESCE(SUM(amount), 0) AS total, COUNT(*) AS cnt " +
            "FROM forum_coin_reward_logs WHERE created_at >= :start AND status <> 'CANCELLED' " +
            "GROUP BY user_id ORDER BY total DESC LIMIT :limit", nativeQuery = true)
    List<Object[]> topUsersSince(@Param("start") LocalDateTime start, @Param("limit") int limit);

    /** Đối soát: tổng coin ĐÃ TỪNG cộng sang IAM (CREDITED hiện tại + đã bị thu hồi). */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM forum_coin_reward_logs " +
            "WHERE status = 'CREDITED' OR revoked_at IS NOT NULL", nativeQuery = true)
    BigDecimal sumEverCredited();

    /** Đối soát: tổng coin đã thu hồi. */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM forum_coin_reward_logs " +
            "WHERE revoked_at IS NOT NULL", nativeQuery = true)
    BigDecimal sumRevoked();

    /** Reward kẹt PENDING quá lâu (RabbitMQ lỗi) — tab vận hành. */
    List<ForumCoinRewardLog> findTop50ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
            ForumCoinRewardLog.RewardStatus status, LocalDateTime before);

    /** Các log CREDITED của 1 user trong khoảng thời gian — thu hồi hàng loạt. */
    List<ForumCoinRewardLog> findByUserIdAndStatusAndCreatedAtBetween(
            Integer userId, ForumCoinRewardLog.RewardStatus status, LocalDateTime from, LocalDateTime to);
}

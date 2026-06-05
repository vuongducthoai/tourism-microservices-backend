package com.tourism.forum.repository;

import com.tourism.forum.entity.ForumRewardAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface ForumRewardAlertRepository extends JpaRepository<ForumRewardAlert, Long> {

    boolean existsByDedupeKey(String dedupeKey);

    Page<ForumRewardAlert> findByStatusOrderByCreatedAtDesc(ForumRewardAlert.AlertStatus status, Pageable pageable);

    Page<ForumRewardAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByStatus(ForumRewardAlert.AlertStatus status);

    // ════════════════ Rule queries (anti-fraud, PLAN_ADMIN_FORUM_COIN §5) ════════════════

    /** R1: user có >= :minDays ngày chạm trần coin trong cửa sổ từ :start. */
    @Query(value = "SELECT user_id, COUNT(*) AS days FROM (" +
            "  SELECT user_id, CAST(created_at AS date) AS d, SUM(amount) AS total" +
            "  FROM forum_coin_reward_logs" +
            "  WHERE created_at >= :start AND status <> 'CANCELLED'" +
            "  GROUP BY user_id, CAST(created_at AS date)" +
            ") t WHERE total >= :cap GROUP BY user_id HAVING COUNT(*) >= :minDays",
            nativeQuery = true)
    List<Object[]> findCapStreakUsers(@Param("start") LocalDateTime start,
                                      @Param("cap") BigDecimal cap,
                                      @Param("minDays") int minDays);

    /** R2: ai like bài của ai >= :minLikes lần từ :since (loại self-like) — Java tìm cặp chéo. */
    @Query(value = "SELECT pl.user_id AS liker, p.user_id AS author, COUNT(*) AS cnt " +
            "FROM post_likes pl JOIN forum_posts p ON p.postid = pl.post_id " +
            "WHERE pl.created_at >= :since AND pl.user_id <> p.user_id " +
            "GROUP BY pl.user_id, p.user_id HAVING COUNT(*) >= :minLikes",
            nativeQuery = true)
    List<Object[]> findHeavyLikers(@Param("since") LocalDateTime since,
                                   @Param("minLikes") int minLikes);

    /** R3: user nhận >= :minFollows thưởng FOLLOW từ :since (mua follow ảo). */
    @Query(value = "SELECT user_id, COUNT(*) AS cnt FROM forum_coin_reward_logs " +
            "WHERE action = 'FOLLOW' AND status <> 'CANCELLED' AND created_at >= :since " +
            "GROUP BY user_id HAVING COUNT(*) >= :minFollows",
            nativeQuery = true)
    List<Object[]> findFollowBurstUsers(@Param("since") LocalDateTime since,
                                        @Param("minFollows") int minFollows);

    /** R4: tỉ lệ comment được thưởng có độ dài sát min (comment đối phó) — Java tính %. */
    @Query(value = "SELECT l.user_id, COUNT(*) AS total, " +
            "  SUM(CASE WHEN LENGTH(TRIM(c.content)) <= :nearMinLen THEN 1 ELSE 0 END) AS short_cnt " +
            "FROM forum_coin_reward_logs l " +
            "JOIN post_comments c ON c.commentid = l.ref_id " +
            "WHERE l.action = 'COMMENT' AND l.created_at >= :since " +
            "GROUP BY l.user_id HAVING COUNT(*) >= :minComments",
            nativeQuery = true)
    List<Object[]> findShortCommentFarmers(@Param("since") LocalDateTime since,
                                           @Param("nearMinLen") int nearMinLen,
                                           @Param("minComments") int minComments);

    /** R5: bài đạt mốc like quá nhanh sau khi đăng (< :minutes phút). */
    @Query(value = "SELECT l.user_id, l.ref_id, l.created_at, p.published_at " +
            "FROM forum_coin_reward_logs l " +
            "JOIN forum_posts p ON p.postid = l.ref_id " +
            "WHERE l.action = 'LIKE_MILESTONE' AND l.created_at >= :since " +
            "  AND p.published_at IS NOT NULL " +
            "  AND l.created_at < p.published_at + (:minutes * INTERVAL '1 minute')",
            nativeQuery = true)
    List<Object[]> findFastMilestones(@Param("since") LocalDateTime since,
                                      @Param("minutes") int minutes);
}

package com.tourism.forum.service;

import com.tourism.forum.config.ForumRewardProperties;
import com.tourism.forum.dto.event.CoinRewardEvent;
import com.tourism.forum.dto.event.ForumNotificationEvent;
import com.tourism.forum.entity.*;
import com.tourism.forum.messaging.ForumEventPublisher;
import com.tourism.forum.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Chính sách thưởng coin khi tương tác forum (PLAN_FORUM_COIN_REWARD.md).
 *
 * Nguyên tắc:
 * - FAIL-OPEN: mọi hook public đều nuốt exception — lỗi thưởng coin
 *   KHÔNG ĐƯỢC làm fail hành động chính (like/comment/post).
 * - Idempotent tuyệt đối qua operationKey (UNIQUE ở cả forum lẫn IAM).
 * - Trần 6 coin/ngày (tính từ DB — nguồn sự thật, không phụ thuộc Redis).
 * - Chỉ thưởng nội dung PUBLISHED (SAFE), không thưởng self-action.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumRewardService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ForumRewardProperties props;
    private final ForumCoinRewardLogRepository rewardLogRepository;
    private final PostRewardMilestoneRepository milestoneRepository;
    private final PostRewardScheduleRepository scheduleRepository;
    private final ForumFollowRewardHistoryRepository followRewardRepository;
    private final ForumPostRepository forumPostRepository;
    private final PostLikeRepository postLikeRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final ContentReportRepository reportRepository;
    private final ForumRewardRestrictionRepository rewardRestrictionRepository;
    private final ForumEventPublisher eventPublisher;
    private final StringRedisTemplate redis;
    /** Self-proxy: để @Transactional(REQUIRES_NEW) của award() có hiệu lực khi gọi nội bộ. */
    private final org.springframework.beans.factory.ObjectProvider<ForumRewardService> self;

    private ForumRewardService proxy() {
        ForumRewardService p = self.getIfAvailable();
        return p != null ? p : this;
    }

    // ════════════════════════════════════════════════════════════════════
    // HOOKS — gọi từ ForumServiceImpl sau hành động chính (fail-open)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Bài viết vừa PUBLISHED → xếp lịch thưởng sau 24h (KHÔNG cộng ngay).
     * Quota: tối đa 3 bài/ngày được xếp lịch thưởng.
     */
    public void onPostPublished(ForumPost post) {
        if (!props.isEnabled() || post == null || post.getUserId() == null) return;
        try {
            if (!ContentStatus.PUBLISHED.equals(post.getStatus())) return;
            if (scheduleRepository.existsByPostId(post.getPostID())) return;

            long scheduledToday = scheduleRepository.countByUserIdAndCreatedAtGreaterThanEqual(
                    post.getUserId(), LocalDate.now().atStartOfDay());
            if (scheduledToday >= props.getMaxRewardedPostsPerDay()) {
                log.info("Reward post quota reached for user {} ({} posts today)", post.getUserId(), scheduledToday);
                return;
            }

            scheduleRepository.save(PostRewardSchedule.builder()
                    .postId(post.getPostID())
                    .userId(post.getUserId())
                    .eligibleAt(LocalDateTime.now().plusHours(props.getPostDelayHours()))
                    .status(PostRewardSchedule.ScheduleStatus.WAITING)
                    .build());
            log.info("Scheduled post reward: postId={}, userId={}, eligible in {}h",
                    post.getPostID(), post.getUserId(), props.getPostDelayHours());

            onDailyInteraction(post.getUserId());
        } catch (Exception e) {
            log.warn("Reward hook onPostPublished failed (fail-open): {}", e.getMessage());
        }
    }

    /**
     * Like bài thay đổi → kiểm tra mốc 5/20/50/100 like TỪ NGƯỜI KHÁC.
     * Mỗi mốc thưởng tác giả đúng 1 lần (PostRewardMilestone UNIQUE).
     */
    public void onPostLikeChanged(ForumPost post, Integer actorUserId) {
        if (!props.isEnabled() || post == null || post.getUserId() == null) return;
        try {
            if (actorUserId != null) onDailyInteraction(actorUserId);
            if (!ContentStatus.PUBLISHED.equals(post.getStatus())) return;

            long othersLikes = postLikeRepository.countByPostPostIDAndUserIdNot(
                    post.getPostID(), post.getUserId());

            for (Integer milestone : props.getPostLikeMilestones()) {
                if (othersLikes < milestone) continue;
                if (milestoneRepository.existsByTargetTypeAndTargetIdAndMilestone(
                        PostRewardMilestone.TargetType.POST, post.getPostID(), milestone)) continue;

                milestoneRepository.save(PostRewardMilestone.builder()
                        .targetType(PostRewardMilestone.TargetType.POST)
                        .targetId(post.getPostID())
                        .milestone(milestone)
                        .build());

                proxy().award(post.getUserId(), props.getPostLikeMilestoneAmount(),
                        ForumCoinRewardLog.RewardAction.LIKE_MILESTONE,
                        "FORUM_LIKE_MILESTONE_" + post.getPostID() + "_" + milestone,
                        "Bài viết \"" + truncate(post.getTitle(), 60) + "\" đạt " + milestone + " lượt thích",
                        post.getPostID());
            }
        } catch (Exception e) {
            log.warn("Reward hook onPostLikeChanged failed (fail-open): {}", e.getMessage());
        }
    }

    /**
     * Comment vừa PUBLISHED (SAFE) → thưởng nếu:
     * không phải comment bài của chính mình, dài ≥ 15 ký tự, còn quota 10/ngày.
     */
    public void onCommentPublished(ForumPost post, PostComment comment) {
        if (!props.isEnabled() || comment == null || comment.getUserId() == null) return;
        try {
            onDailyInteraction(comment.getUserId());

            if (comment.getStatus() != null && !ContentStatus.PUBLISHED.equals(comment.getStatus())) return;
            // Self-action: comment bài của chính mình → không thưởng
            if (post != null && comment.getUserId().equals(post.getUserId())) return;

            String plain = stripHtml(comment.getContent());
            if (plain.length() < props.getMinCommentLength()) return;

            long rewardedToday = rewardLogRepository.countByUserIdAndActionAndCreatedAtGreaterThanEqual(
                    comment.getUserId(), ForumCoinRewardLog.RewardAction.COMMENT, LocalDate.now().atStartOfDay());
            if (rewardedToday >= props.getMaxRewardedCommentsPerDay()) return;

            proxy().award(comment.getUserId(), props.getCommentAmount(),
                    ForumCoinRewardLog.RewardAction.COMMENT,
                    "FORUM_COMMENT_REWARD_" + comment.getCommentID(),
                    "Bình luận được duyệt" + (post != null ? " trong bài \"" + truncate(post.getTitle(), 60) + "\"" : ""),
                    comment.getCommentID());
        } catch (Exception e) {
            log.warn("Reward hook onCommentPublished failed (fail-open): {}", e.getMessage());
        }
    }

    /** Like comment thay đổi → kiểm tra mốc 5/15 like từ người khác cho tác giả comment. */
    public void onCommentLikeChanged(PostComment comment, Integer actorUserId) {
        if (!props.isEnabled() || comment == null || comment.getUserId() == null) return;
        try {
            if (actorUserId != null) onDailyInteraction(actorUserId);
            if (comment.getStatus() != null && !ContentStatus.PUBLISHED.equals(comment.getStatus())) return;

            long othersLikes = commentLikeRepository.countByCommentCommentIDAndUserIdNot(
                    comment.getCommentID(), comment.getUserId());

            for (Integer milestone : props.getCommentLikeMilestones()) {
                if (othersLikes < milestone) continue;
                if (milestoneRepository.existsByTargetTypeAndTargetIdAndMilestone(
                        PostRewardMilestone.TargetType.COMMENT, comment.getCommentID(), milestone)) continue;

                milestoneRepository.save(PostRewardMilestone.builder()
                        .targetType(PostRewardMilestone.TargetType.COMMENT)
                        .targetId(comment.getCommentID())
                        .milestone(milestone)
                        .build());

                proxy().award(comment.getUserId(), props.getCommentLikeMilestoneAmount(),
                        ForumCoinRewardLog.RewardAction.COMMENT_LIKE_MILESTONE,
                        "FORUM_COMMENT_LIKE_MILESTONE_" + comment.getCommentID() + "_" + milestone,
                        "Bình luận của bạn đạt " + milestone + " lượt thích",
                        comment.getCommentID());
            }
        } catch (Exception e) {
            log.warn("Reward hook onCommentLikeChanged failed (fail-open): {}", e.getMessage());
        }
    }

    /**
     * Có follow MỚI → thưởng NGƯỜI ĐƯỢC FOLLOW (followingId).
     * Mỗi cặp follower→following chỉ thưởng 1 lần TRỌN ĐỜI; tối đa 10 lần/ngày.
     */
    public void onNewFollow(Integer followerId, Integer followingId) {
        if (!props.isEnabled() || followerId == null || followingId == null) return;
        try {
            onDailyInteraction(followerId);
            if (followerId.equals(followingId)) return;
            if (followRewardRepository.existsByFollowerIdAndFollowingId(followerId, followingId)) return;

            long rewardedToday = rewardLogRepository.countByUserIdAndActionAndCreatedAtGreaterThanEqual(
                    followingId, ForumCoinRewardLog.RewardAction.FOLLOW, LocalDate.now().atStartOfDay());
            if (rewardedToday >= props.getMaxFollowRewardsPerDay()) return;

            followRewardRepository.save(ForumFollowRewardHistory.builder()
                    .followerId(followerId)
                    .followingId(followingId)
                    .build());

            proxy().award(followingId, props.getFollowAmount(),
                    ForumCoinRewardLog.RewardAction.FOLLOW,
                    "FORUM_FOLLOW_" + followerId + "_" + followingId,
                    "Có người mới theo dõi bạn",
                    followerId);
        } catch (Exception e) {
            log.warn("Reward hook onNewFollow failed (fail-open): {}", e.getMessage());
        }
    }

    /**
     * Daily streak: 1 lần/ngày khi user có ≥1 tương tác hợp lệ.
     * +0.5 coin; mỗi chuỗi đủ 7 ngày liên tiếp → bonus thêm +2.
     * Idempotent qua operationKey FORUM_DAILY_{userId}_{yyyyMMdd}.
     */
    public void onDailyInteraction(Integer userId) {
        if (!props.isEnabled() || userId == null) return;
        try {
            LocalDate today = LocalDate.now();
            String todayKey = "FORUM_DAILY_" + userId + "_" + today.format(DAY_FMT);
            if (rewardLogRepository.existsByOperationKey(todayKey)) return;

            // Tính chuỗi: hôm qua có nhận daily không?
            String yesterdayKey = "FORUM_DAILY_" + userId + "_" + today.minusDays(1).format(DAY_FMT);
            boolean continued = rewardLogRepository.existsByOperationKey(yesterdayKey);

            int streak = 1;
            String streakRedisKey = "forum:coin:streak:" + userId;
            if (continued) {
                try {
                    String prev = redis.opsForValue().get(streakRedisKey);
                    streak = (prev == null ? 1 : Integer.parseInt(prev)) + 1;
                } catch (Exception ignore) {
                    streak = 2; // Redis lỗi → ước lượng tối thiểu, fail-open
                }
            }

            BigDecimal amount = props.getDailyAmount();
            boolean bonus = props.getStreakLength() > 0 && streak % props.getStreakLength() == 0;
            if (bonus) amount = amount.add(props.getStreakBonus());

            boolean awarded = proxy().award(userId, amount,
                    ForumCoinRewardLog.RewardAction.DAILY,
                    todayKey,
                    "Hoạt động hằng ngày (chuỗi " + streak + " ngày)" + (bonus ? " — bonus chuỗi " + props.getStreakLength() + " ngày!" : ""),
                    null);

            if (awarded) {
                try {
                    redis.opsForValue().set(streakRedisKey, String.valueOf(streak), Duration.ofHours(48));
                } catch (Exception ignore) { /* fail-open */ }
            }
        } catch (Exception e) {
            log.warn("Reward hook onDailyInteraction failed (fail-open): {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // SCHEDULER ENTRY POINTS (gọi từ ForumRewardScheduler)
    // ════════════════════════════════════════════════════════════════════

    /** Xử lý các bài đã đủ 24h: vẫn PUBLISHED & không có report chờ → thưởng tác giả. */
    @Transactional
    public void processDuePostRewards() {
        if (!props.isEnabled()) return;
        List<PostRewardSchedule> due = scheduleRepository.findByStatusAndEligibleAtBefore(
                PostRewardSchedule.ScheduleStatus.WAITING, LocalDateTime.now(), PageRequest.of(0, 50));

        for (PostRewardSchedule schedule : due) {
            try {
                ForumPost post = forumPostRepository.findById(schedule.getPostId()).orElse(null);

                boolean deleted = post == null || Boolean.TRUE.equals(post.getIsDeleted());
                boolean published = post != null && ContentStatus.PUBLISHED.equals(post.getStatus());

                if (deleted || (post != null && (ContentStatus.HIDDEN.equals(post.getStatus())))) {
                    schedule.setStatus(PostRewardSchedule.ScheduleStatus.CANCELLED);
                    scheduleRepository.save(schedule);
                    log.info("Post reward cancelled (post hidden/deleted): postId={}", schedule.getPostId());
                    continue;
                }
                if (!published) {
                    // DRAFT / PENDING_REVIEW → chưa kết luận, chờ vòng sau
                    continue;
                }
                // Có report đang chờ xử lý → hoãn đến khi admin kết luận
                if (reportRepository.existsByTargetTypeAndTargetIdAndStatus(
                        ModerationAuditLog.TargetType.POST, post.getPostID(), ReportStatus.PENDING)) {
                    log.info("Post reward postponed (pending report): postId={}", post.getPostID());
                    continue;
                }

                boolean awarded = proxy().award(schedule.getUserId(), props.getPostAmount(),
                        ForumCoinRewardLog.RewardAction.POST,
                        "FORUM_POST_REWARD_" + post.getPostID(),
                        "Bài viết \"" + truncate(post.getTitle(), 60) + "\" được duyệt và giữ vững sau "
                                + props.getPostDelayHours() + "h",
                        post.getPostID());

                // awarded=false do trần ngày → vẫn đóng schedule (không thưởng lại — im lặng theo §2)
                schedule.setStatus(PostRewardSchedule.ScheduleStatus.REWARDED);
                scheduleRepository.save(schedule);
                if (!awarded) {
                    log.info("Post reward skipped by daily cap/idempotency: postId={}", post.getPostID());
                }
            } catch (Exception e) {
                log.warn("Failed to process post reward schedule id={}: {}", schedule.getId(), e.getMessage());
            }
        }
    }

    /** Retry các bản ghi PENDING (publish RabbitMQ thất bại trước đó) — mini-outbox. */
    @Transactional
    public void retryPendingRewards() {
        if (!props.isEnabled()) return;
        List<ForumCoinRewardLog> pending = rewardLogRepository.findByStatusAndCreatedAtBefore(
                ForumCoinRewardLog.RewardStatus.PENDING,
                LocalDateTime.now().minusMinutes(5), PageRequest.of(0, 50));

        for (ForumCoinRewardLog logEntry : pending) {
            try {
                boolean sent = eventPublisher.publishCoinReward(toEvent(logEntry));
                if (sent) {
                    logEntry.setStatus(ForumCoinRewardLog.RewardStatus.CREDITED);
                    rewardLogRepository.save(logEntry);
                    publishRewardNotification(logEntry);
                    log.info("Retried pending coin reward OK: key={}", logEntry.getOperationKey());
                }
            } catch (Exception e) {
                log.warn("Retry pending reward failed: key={}, error={}", logEntry.getOperationKey(), e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CORE
    // ════════════════════════════════════════════════════════════════════

    /**
     * Hàm thưởng lõi: idempotency + trần ngày + ghi log audit + publish event.
     * REQUIRES_NEW: log thưởng được commit độc lập với transaction của hành động chính.
     * @return true nếu đã thưởng (log được ghi).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean award(Integer userId, BigDecimal amount, ForumCoinRewardLog.RewardAction action,
                         String operationKey, String reason, Integer refId) {
        if (!props.isEnabled() || userId == null || amount == null || amount.signum() <= 0) return false;

        // 0. User bị admin khóa thưởng → bỏ qua (im lặng — hành động forum vẫn chạy)
        if (isRewardRestricted(userId)) {
            log.info("Reward skipped (user restricted): userId={}, key={}", userId, operationKey);
            return false;
        }

        // 1. Idempotency: operationKey đã tồn tại → bỏ qua tuyệt đối
        if (rewardLogRepository.existsByOperationKey(operationKey)) return false;

        // 2. Trần ngày (DB là nguồn sự thật): vượt → im lặng, không báo lỗi
        BigDecimal earnedToday = rewardLogRepository.sumAmountSince(userId, LocalDate.now().atStartOfDay());
        if (earnedToday.add(amount).compareTo(props.getDailyCap()) > 0) {
            log.info("Daily coin cap reached for user {} ({} + {} > {})", userId, earnedToday, amount, props.getDailyCap());
            return false;
        }

        // 3. Ghi log audit (PENDING)
        ForumCoinRewardLog logEntry;
        try {
            // saveAndFlush để UNIQUE(operation_key) được kiểm tra ngay (chống race)
            logEntry = rewardLogRepository.saveAndFlush(ForumCoinRewardLog.builder()
                    .userId(userId)
                    .action(action)
                    .amount(amount)
                    .operationKey(operationKey)
                    .reason(reason)
                    .status(ForumCoinRewardLog.RewardStatus.PENDING)
                    .refId(refId)
                    .build());
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Race condition: request song song đã ghi rồi → idempotent, bỏ qua
            log.info("Concurrent duplicate reward ignored: key={}", operationKey);
            return false;
        }

        // 4. Publish event cộng coin sang IAM (fire & forget, retry qua scheduler)
        boolean sent = eventPublisher.publishCoinReward(toEvent(logEntry));
        if (sent) {
            logEntry.setStatus(ForumCoinRewardLog.RewardStatus.CREDITED);
            rewardLogRepository.save(logEntry);
            publishRewardNotification(logEntry);
        }
        // sent=false → giữ PENDING, retryPendingRewards() sẽ gửi lại

        log.info("Coin reward awarded: userId={}, amount={}, action={}, key={}, sent={}",
                userId, amount, action, operationKey, sent);
        return true;
    }

    /** Thông báo "Bạn nhận được X coin" qua notification-service. */
    private void publishRewardNotification(ForumCoinRewardLog logEntry) {
        try {
            eventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                    .idempotencyKey("COIN_REWARD-" + logEntry.getOperationKey())
                    .eventType("COIN_REWARD")
                    .recipientUserId(logEntry.getUserId())
                    .actorUserId(null)
                    .actorName("Hệ thống thưởng coin")
                    .postId(logEntry.getAction() == ForumCoinRewardLog.RewardAction.POST
                            || logEntry.getAction() == ForumCoinRewardLog.RewardAction.LIKE_MILESTONE
                            ? logEntry.getRefId() : null)
                    .postTitle(logEntry.getReason())
                    .coinAmount(logEntry.getAmount())
                    .build());
        } catch (Exception e) {
            log.warn("Failed to publish coin reward notification (non-critical): {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // API hỗ trợ FE: GET coin-summary
    // ════════════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getCoinSummary(Integer userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", props.isEnabled());
        result.put("dailyCap", props.getDailyCap());

        if (userId == null) {
            result.put("todayEarned", BigDecimal.ZERO);
            result.put("totalFromForum", BigDecimal.ZERO);
            result.put("recentRewards", List.of());
            return result;
        }

        result.put("todayEarned", rewardLogRepository.sumAmountSince(userId, LocalDate.now().atStartOfDay()));
        result.put("totalFromForum", rewardLogRepository.sumTotalCredited(userId));

        // ── Daily streak hiện tại (cho thanh tiến trình FE) ──
        LocalDate today = LocalDate.now();
        boolean todayDone = rewardLogRepository.existsByOperationKey(
                "FORUM_DAILY_" + userId + "_" + today.format(DAY_FMT));
        boolean yesterdayDone = rewardLogRepository.existsByOperationKey(
                "FORUM_DAILY_" + userId + "_" + today.minusDays(1).format(DAY_FMT));
        int streak = 0;
        try {
            String v = redis.opsForValue().get("forum:coin:streak:" + userId);
            if (v != null) streak = Integer.parseInt(v);
        } catch (Exception ignore) { /* fail-open */ }
        if (!todayDone && !yesterdayDone) streak = 0;          // chuỗi đã đứt
        if (todayDone && streak == 0) streak = 1;              // Redis mất → tối thiểu 1
        result.put("streak", streak);
        result.put("streakTodayDone", todayDone);
        result.put("recentRewards", rewardLogRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("action", l.getAction().name());
                    m.put("amount", l.getAmount());
                    m.put("reason", l.getReason());
                    m.put("status", l.getStatus().name());
                    m.put("createdAt", l.getCreatedAt());
                    return m;
                })
                .collect(Collectors.toList()));

        // Cấu hình hiển thị chính sách cho FE (modal "Cách kiếm coin")
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("postAmount", props.getPostAmount());
        policy.put("postDelayHours", props.getPostDelayHours());
        policy.put("maxRewardedPostsPerDay", props.getMaxRewardedPostsPerDay());
        policy.put("postLikeMilestones", props.getPostLikeMilestones());
        policy.put("postLikeMilestoneAmount", props.getPostLikeMilestoneAmount());
        policy.put("commentAmount", props.getCommentAmount());
        policy.put("minCommentLength", props.getMinCommentLength());
        policy.put("maxRewardedCommentsPerDay", props.getMaxRewardedCommentsPerDay());
        policy.put("commentLikeMilestones", props.getCommentLikeMilestones());
        policy.put("commentLikeMilestoneAmount", props.getCommentLikeMilestoneAmount());
        policy.put("followAmount", props.getFollowAmount());
        policy.put("dailyAmount", props.getDailyAmount());
        policy.put("streakBonus", props.getStreakBonus());
        policy.put("streakLength", props.getStreakLength());
        result.put("policy", policy);

        return result;
    }

    /** Lịch sử thưởng coin đầy đủ, phân trang — cho popover "Xem lịch sử thưởng". */
    @Transactional(readOnly = true)
    public Map<String, Object> getCoinHistory(Integer userId, int page, int size) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (userId == null) {
            result.put("items", List.of());
            result.put("page", 0);
            result.put("totalPages", 0);
            result.put("totalElements", 0L);
            result.put("hasNext", false);
            return result;
        }

        int safeSize = Math.min(Math.max(size, 1), 50);
        org.springframework.data.domain.Page<ForumCoinRewardLog> p =
                rewardLogRepository.findByUserIdOrderByCreatedAtDesc(
                        userId, PageRequest.of(Math.max(page, 0), safeSize));

        result.put("items", p.getContent().stream()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("action", l.getAction().name());
                    m.put("amount", l.getAmount());
                    m.put("reason", l.getReason());
                    m.put("status", l.getStatus().name());
                    m.put("createdAt", l.getCreatedAt());
                    return m;
                })
                .collect(Collectors.toList()));
        result.put("page", p.getNumber());
        result.put("totalPages", p.getTotalPages());
        result.put("totalElements", p.getTotalElements());
        result.put("hasNext", p.hasNext());
        return result;
    }

    /** User đang bị admin khóa thưởng coin? Khóa hết hạn → tự gỡ. */
    private boolean isRewardRestricted(Integer userId) {
        try {
            return rewardRestrictionRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                    .map(r -> {
                        if (r.getBannedUntil() != null && r.getBannedUntil().isBefore(LocalDateTime.now())) {
                            r.setActive(false);
                            rewardRestrictionRepository.save(r);
                            return false;
                        }
                        return true;
                    })
                    .orElse(false);
        } catch (Exception e) {
            log.warn("Reward restriction check failed (fail-open, vẫn cho thưởng): {}", e.getMessage());
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private CoinRewardEvent toEvent(ForumCoinRewardLog logEntry) {
        return CoinRewardEvent.builder()
                .operationKey(logEntry.getOperationKey())
                .userId(logEntry.getUserId())
                .amount(logEntry.getAmount())
                .action(logEntry.getAction().name())
                .reason(logEntry.getReason())
                .refId(logEntry.getRefId())
                .build();
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("&[a-zA-Z]+;", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}

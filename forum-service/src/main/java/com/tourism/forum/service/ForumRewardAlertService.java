package com.tourism.forum.service;

import com.tourism.forum.config.AdminContext;
import com.tourism.forum.config.ForumRewardProperties;
import com.tourism.forum.entity.ForumRewardAlert;
import com.tourism.forum.feign.IamFeignClient;
import com.tourism.forum.feign.dto.UserBriefResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Quét các rule anti-fraud và sinh cảnh báo (PLAN_ADMIN_FORUM_COIN §5).
 * Mỗi rule fail-open riêng — 1 rule lỗi không chặn các rule khác.
 * Dedupe qua dedupeKey UNIQUE (mỗi sự kiện chỉ cảnh báo 1 lần/ngày).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumRewardAlertService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final com.tourism.forum.repository.ForumRewardAlertRepository alertRepository;
    private final ForumRewardProperties props;
    private final IamFeignClient iamFeignClient;

    private final Map<Integer, UserBriefResponse> userCache = new ConcurrentHashMap<>();

    // ════════════════ CHẠY RULES ════════════════

    /** Chạy toàn bộ rule. Trả về số cảnh báo mới được sinh. */
    @Transactional
    public int runRules() {
        int created = 0;
        created += safeRun(this::ruleCapStreak, "CAP_STREAK");
        created += safeRun(this::ruleCrossLike, "CROSS_LIKE");
        created += safeRun(this::ruleFollowBurst, "FOLLOW_BURST");
        created += safeRun(this::ruleShortComment, "SHORT_COMMENT");
        created += safeRun(this::ruleFastMilestone, "FAST_MILESTONE");
        if (created > 0) {
            log.info("Reward alert scan finished: {} new alerts", created);
        }
        return created;
    }

    private int safeRun(java.util.function.IntSupplier rule, String code) {
        try {
            return rule.getAsInt();
        } catch (Exception e) {
            log.warn("Alert rule {} failed: {}", code, e.getMessage());
            return 0;
        }
    }

    /** R1: chạm trần coin >= 5 ngày trong 5 ngày gần nhất. */
    private int ruleCapStreak() {
        int minDays = 5;
        LocalDateTime start = LocalDate.now().minusDays(minDays - 1).atStartOfDay();
        int created = 0;
        for (Object[] row : alertRepository.findCapStreakUsers(start, props.getDailyCap(), minDays)) {
            Integer userId = ((Number) row[0]).intValue();
            long days = ((Number) row[1]).longValue();
            created += saveAlert(ForumRewardAlert.builder()
                    .ruleCode("CAP_STREAK")
                    .severity(ForumRewardAlert.Severity.MEDIUM)
                    .userId(userId)
                    .message("User chạm trần " + props.getDailyCap() + " coin/ngày " + days
                            + " ngày liên tiếp — khả năng cày tương tác")
                    .dedupeKey("CAP_STREAK_" + userId + "_" + LocalDate.now().format(DAY_FMT))
                    .build());
        }
        return created;
    }

    /** R2: cặp user like chéo nhau nhiều bất thường trong 24h. */
    private int ruleCrossLike() {
        int minLikes = 5;
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<Object[]> rows = alertRepository.findHeavyLikers(since, minLikes);

        // map (liker -> author) -> count
        Map<String, Long> likeMap = new HashMap<>();
        for (Object[] row : rows) {
            likeMap.put(((Number) row[0]).intValue() + ">" + ((Number) row[1]).intValue(),
                    ((Number) row[2]).longValue());
        }

        int created = 0;
        Set<String> seenPairs = new HashSet<>();
        for (Object[] row : rows) {
            int a = ((Number) row[0]).intValue();
            int b = ((Number) row[1]).intValue();
            if (!likeMap.containsKey(b + ">" + a)) continue; // không chéo
            String pairKey = Math.min(a, b) + "_" + Math.max(a, b);
            if (!seenPairs.add(pairKey)) continue; // đã xử lý cặp này

            created += saveAlert(ForumRewardAlert.builder()
                    .ruleCode("CROSS_LIKE")
                    .severity(ForumRewardAlert.Severity.HIGH)
                    .userId(Math.min(a, b))
                    .relatedUserId(Math.max(a, b))
                    .message("Cặp user #" + a + " và #" + b + " like chéo bài của nhau ≥ " + minLikes
                            + " lần/24h — nghi cày mốc like")
                    .dedupeKey("CROSS_LIKE_" + pairKey + "_" + LocalDate.now().format(DAY_FMT))
                    .build());
        }
        return created;
    }

    /** R3: nhận nhiều thưởng FOLLOW trong 24h (mua follow ảo). */
    private int ruleFollowBurst() {
        int minFollows = Math.max(5, props.getMaxFollowRewardsPerDay() - 2);
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        int created = 0;
        for (Object[] row : alertRepository.findFollowBurstUsers(since, minFollows)) {
            Integer userId = ((Number) row[0]).intValue();
            long cnt = ((Number) row[1]).longValue();
            created += saveAlert(ForumRewardAlert.builder()
                    .ruleCode("FOLLOW_BURST")
                    .severity(ForumRewardAlert.Severity.HIGH)
                    .userId(userId)
                    .message("User nhận " + cnt + " thưởng follow trong 24h — nghi tạo tài khoản ảo follow")
                    .dedupeKey("FOLLOW_BURST_" + userId + "_" + LocalDate.now().format(DAY_FMT))
                    .build());
        }
        return created;
    }

    /** R4: > 80% comment được thưởng có độ dài sát mức tối thiểu (7 ngày). */
    private int ruleShortComment() {
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        int nearMin = props.getMinCommentLength() + 5; // nới nhẹ vì content có thể chứa HTML
        int created = 0;
        for (Object[] row : alertRepository.findShortCommentFarmers(since, nearMin, 5)) {
            Integer userId = ((Number) row[0]).intValue();
            long total = ((Number) row[1]).longValue();
            long shortCnt = ((Number) row[2]).longValue();
            if (total == 0 || (double) shortCnt / total <= 0.8) continue;

            created += saveAlert(ForumRewardAlert.builder()
                    .ruleCode("SHORT_COMMENT")
                    .severity(ForumRewardAlert.Severity.MEDIUM)
                    .userId(userId)
                    .message("" + shortCnt + "/" + total + " comment được thưởng (7 ngày) chỉ dài sát mức tối thiểu "
                            + props.getMinCommentLength() + " ký tự — nghi comment đối phó")
                    .dedupeKey("SHORT_COMMENT_" + userId + "_" + LocalDate.now().format(DAY_FMT))
                    .build());
        }
        return created;
    }

    /** R5: bài đạt mốc like trong < 2 phút sau khi đăng. */
    private int ruleFastMilestone() {
        int minutes = 2;
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        int created = 0;
        for (Object[] row : alertRepository.findFastMilestones(since, minutes)) {
            Integer userId = ((Number) row[0]).intValue();
            Integer postId = ((Number) row[1]).intValue();
            created += saveAlert(ForumRewardAlert.builder()
                    .ruleCode("FAST_MILESTONE")
                    .severity(ForumRewardAlert.Severity.HIGH)
                    .userId(userId)
                    .refId(postId)
                    .message("Bài #" + postId + " đạt mốc like chỉ trong < " + minutes
                            + " phút sau khi đăng — nghi like ảo có tổ chức")
                    .dedupeKey("FAST_MILESTONE_" + postId)
                    .build());
        }
        return created;
    }

    /** Lưu alert, bỏ qua nếu dedupeKey đã tồn tại. Trả 1 nếu tạo mới. */
    private int saveAlert(ForumRewardAlert alert) {
        try {
            if (alertRepository.existsByDedupeKey(alert.getDedupeKey())) return 0;
            alertRepository.save(alert);
            return 1;
        } catch (DataIntegrityViolationException dup) {
            return 0; // race — đã có
        }
    }

    // ════════════════ API CHO ADMIN ════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getAlerts(String status, int page, int size) {
        Page<ForumRewardAlert> p;
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        if (status != null && !status.isBlank()) {
            ForumRewardAlert.AlertStatus s;
            try {
                s = ForumRewardAlert.AlertStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                s = ForumRewardAlert.AlertStatus.NEW;
            }
            p = alertRepository.findByStatusOrderByCreatedAtDesc(s, pr);
        } else {
            p = alertRepository.findAllByOrderByCreatedAtDesc(pr);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", p.getContent().stream().map(this::mapAlert).collect(Collectors.toList()));
        result.put("page", p.getNumber());
        result.put("totalPages", p.getTotalPages());
        result.put("totalElements", p.getTotalElements());
        result.put("newCount", alertRepository.countByStatus(ForumRewardAlert.AlertStatus.NEW));
        return result;
    }

    @Transactional
    public void resolveAlert(Long alertId) {
        ForumRewardAlert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cảnh báo: " + alertId));
        alert.setStatus(ForumRewardAlert.AlertStatus.RESOLVED);
        alert.setResolvedBy(AdminContext.currentUserId());
        alert.setResolvedAt(LocalDateTime.now());
        alertRepository.save(alert);
    }

    private Map<String, Object> mapAlert(ForumRewardAlert a) {
        UserBriefResponse user = getUserSafe(a.getUserId());
        UserBriefResponse related = getUserSafe(a.getRelatedUserId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("ruleCode", a.getRuleCode());
        m.put("severity", a.getSeverity().name());
        m.put("userId", a.getUserId());
        m.put("userName", user != null ? user.getFullName() : null);
        m.put("userAvatar", user != null ? user.getAvatar() : null);
        m.put("relatedUserId", a.getRelatedUserId());
        m.put("relatedUserName", related != null ? related.getFullName() : null);
        m.put("refId", a.getRefId());
        m.put("message", a.getMessage());
        m.put("status", a.getStatus().name());
        m.put("resolvedBy", a.getResolvedBy());
        m.put("resolvedAt", a.getResolvedAt());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private UserBriefResponse getUserSafe(Integer userId) {
        if (userId == null) return null;
        return userCache.computeIfAbsent(userId, id -> {
            try {
                return iamFeignClient.getUserById(id);
            } catch (Exception e) {
                return null;
            }
        });
    }
}

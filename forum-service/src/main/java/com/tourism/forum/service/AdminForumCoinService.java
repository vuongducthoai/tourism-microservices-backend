package com.tourism.forum.service;

import com.tourism.forum.config.AdminContext;
import com.tourism.forum.dto.event.ForumNotificationEvent;
import com.tourism.forum.entity.ForumCoinRewardLog;
import com.tourism.forum.entity.ForumRewardRestriction;
import com.tourism.forum.feign.IamFeignClient;
import com.tourism.forum.feign.dto.UserBriefResponse;
import com.tourism.forum.messaging.ForumEventPublisher;
import com.tourism.forum.repository.ForumCoinRewardLogRepository;
import com.tourism.forum.repository.ForumRewardRestrictionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Quản lý coin forum cho ADMIN (PLAN_ADMIN_FORUM_COIN — Đợt 1 + 2):
 * log viewer, khóa thưởng, thu hồi coin, dashboard thống kê, hàng kẹt, đối soát.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminForumCoinService {

    private final ForumCoinRewardLogRepository rewardLogRepository;
    private final ForumRewardRestrictionRepository restrictionRepository;
    private final IamFeignClient iamFeignClient;
    private final ForumEventPublisher eventPublisher;

    // Cache user info (tránh Feign lặp khi render bảng)
    private final Map<Integer, UserBriefResponse> userCache = new ConcurrentHashMap<>();

    // ════════════════ §2 LOG VIEWER ════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getLogs(Integer userId, String action, String status,
                                       LocalDate from, LocalDate to, int page, int size) {
        Specification<ForumCoinRewardLog> spec = Specification.where(null);

        if (userId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("userId"), userId));
        }
        if (action != null && !action.isBlank()) {
            try {
                ForumCoinRewardLog.RewardAction a = ForumCoinRewardLog.RewardAction.valueOf(action.toUpperCase());
                spec = spec.and((root, q, cb) -> cb.equal(root.get("action"), a));
            } catch (IllegalArgumentException ignored) { /* action không hợp lệ → bỏ filter */ }
        }
        if (status != null && !status.isBlank()) {
            try {
                ForumCoinRewardLog.RewardStatus s = ForumCoinRewardLog.RewardStatus.valueOf(status.toUpperCase());
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), s));
            } catch (IllegalArgumentException ignored) { }
        }
        if (from != null) {
            LocalDateTime f = from.atStartOfDay();
            spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), f));
        }
        if (to != null) {
            LocalDateTime t = to.plusDays(1).atStartOfDay();
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("createdAt"), t));
        }

        Page<ForumCoinRewardLog> p = rewardLogRepository.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", p.getContent().stream().map(this::mapLog).collect(Collectors.toList()));
        result.put("page", p.getNumber());
        result.put("totalPages", p.getTotalPages());
        result.put("totalElements", p.getTotalElements());
        return result;
    }

    private Map<String, Object> mapLog(ForumCoinRewardLog l) {
        UserBriefResponse user = getUserSafe(l.getUserId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("userId", l.getUserId());
        m.put("userName", user != null ? user.getFullName() : null);
        m.put("userAvatar", user != null ? user.getAvatar() : null);
        m.put("action", l.getAction().name());
        m.put("amount", l.getAmount());
        m.put("reason", l.getReason());
        m.put("status", l.getStatus().name());
        m.put("operationKey", l.getOperationKey());
        m.put("refId", l.getRefId());
        m.put("createdAt", l.getCreatedAt());
        m.put("revokedBy", l.getRevokedBy());
        m.put("revokedAt", l.getRevokedAt());
        m.put("revokeReason", l.getRevokeReason());
        return m;
    }

    // ════════════════ §3 KHÓA THƯỞNG ════════════════

    @Transactional
    public void restrictUser(Integer userId, String reason, Integer durationDays) {
        if (userId == null) throw new RuntimeException("Thiếu userId");

        // Gỡ khóa cũ (nếu có) trước khi tạo khóa mới
        restrictionRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .ifPresent(old -> {
                    old.setActive(false);
                    restrictionRepository.save(old);
                });

        LocalDateTime until = (durationDays != null && durationDays > 0)
                ? LocalDateTime.now().plusDays(durationDays)
                : null; // null = vĩnh viễn

        restrictionRepository.save(ForumRewardRestriction.builder()
                .userId(userId)
                .reason(reason)
                .bannedUntil(until)
                .bannedBy(AdminContext.currentUserId())
                .active(true)
                .build());
        log.info("Admin {} restricted forum coin reward for user {} ({})",
                AdminContext.currentUserId(), userId, until == null ? "permanent" : "until " + until);
    }

    @Transactional
    public void unrestrictUser(Integer userId) {
        restrictionRepository.findFirstByUserIdAndActiveTrueOrderByCreatedAtDesc(userId)
                .ifPresent(r -> {
                    r.setActive(false);
                    restrictionRepository.save(r);
                    log.info("Admin {} lifted coin reward restriction for user {}",
                            AdminContext.currentUserId(), userId);
                });
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRestrictions() {
        return restrictionRepository.findByActiveTrueOrderByCreatedAtDesc().stream()
                .map(r -> {
                    UserBriefResponse user = getUserSafe(r.getUserId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("userId", r.getUserId());
                    m.put("userName", user != null ? user.getFullName() : null);
                    m.put("userAvatar", user != null ? user.getAvatar() : null);
                    m.put("reason", r.getReason());
                    m.put("bannedUntil", r.getBannedUntil());
                    m.put("bannedBy", r.getBannedBy());
                    m.put("createdAt", r.getCreatedAt());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ════════════════ §4 THU HỒI COIN (clawback) ════════════════

    /**
     * Thu hồi 1 lượt thưởng: trừ coin bên IAM (idempotent qua REVOKE_{key}),
     * set log CANCELLED + audit. Nếu số dư không đủ → trừ tới mức còn lại (ghi chú phần thiếu).
     */
    @Transactional
    public Map<String, Object> revoke(Long logId, String reason) {
        ForumCoinRewardLog logEntry = rewardLogRepository.findById(logId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy bản ghi thưởng: " + logId));
        if (logEntry.getStatus() != ForumCoinRewardLog.RewardStatus.CREDITED) {
            throw new RuntimeException("Chỉ thu hồi được lượt thưởng đã CREDITED (hiện tại: "
                    + logEntry.getStatus() + ")");
        }

        BigDecimal toDeduct = logEntry.getAmount();
        String note = "";

        // Số dư không đủ → trừ tới 0, ghi chú phần thiếu
        try {
            UserBriefResponse user = iamFeignClient.getUserById(logEntry.getUserId());
            BigDecimal balance = user != null && user.getCoinBalance() != null
                    ? user.getCoinBalance() : BigDecimal.ZERO;
            if (balance.compareTo(toDeduct) < 0) {
                note = " (số dư chỉ còn " + balance + ", thiếu " + toDeduct.subtract(balance) + ")";
                toDeduct = balance;
            }
        } catch (Exception e) {
            log.warn("Không đọc được số dư user {} — thử trừ đủ: {}", logEntry.getUserId(), e.getMessage());
        }

        if (toDeduct.signum() > 0) {
            iamFeignClient.deductCoins(logEntry.getUserId(), toDeduct,
                    "REVOKE_" + logEntry.getOperationKey());
        }

        logEntry.setStatus(ForumCoinRewardLog.RewardStatus.CANCELLED);
        logEntry.setRevokedBy(AdminContext.currentUserId());
        logEntry.setRevokedAt(LocalDateTime.now());
        logEntry.setRevokeReason((reason != null ? reason : "Vi phạm chính sách") + note);
        rewardLogRepository.save(logEntry);

        // Thông báo cho user
        try {
            eventPublisher.publishForumEvent(ForumNotificationEvent.builder()
                    .idempotencyKey("COIN_REVOKED-" + logEntry.getOperationKey())
                    .eventType("COIN_REVOKED")
                    .recipientUserId(logEntry.getUserId())
                    .actorName("Quản trị viên")
                    .postTitle((reason != null ? reason : "Vi phạm chính sách thưởng coin"))
                    .coinAmount(toDeduct)
                    .build());
        } catch (Exception e) {
            log.warn("Không gửi được notification thu hồi (non-critical): {}", e.getMessage());
        }

        log.info("Admin {} revoked reward log {} (user={}, amount={}{})",
                AdminContext.currentUserId(), logId, logEntry.getUserId(), toDeduct, note);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revokedAmount", toDeduct);
        result.put("note", note.isBlank() ? null : note.trim());
        return result;
    }

    /** Thu hồi hàng loạt: toàn bộ lượt CREDITED của 1 user trong khoảng ngày. */
    @Transactional
    public Map<String, Object> revokeBulk(Integer userId, LocalDate from, LocalDate to, String reason) {
        if (userId == null) throw new RuntimeException("Thiếu userId");
        LocalDateTime f = (from != null ? from : LocalDate.now().minusDays(30)).atStartOfDay();
        LocalDateTime t = (to != null ? to.plusDays(1) : LocalDate.now().plusDays(1)).atStartOfDay();

        List<ForumCoinRewardLog> logs = rewardLogRepository.findByUserIdAndStatusAndCreatedAtBetween(
                userId, ForumCoinRewardLog.RewardStatus.CREDITED, f, t);

        int ok = 0, fail = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (ForumCoinRewardLog l : logs) {
            try {
                Map<String, Object> r = revoke(l.getId(), reason);
                total = total.add((BigDecimal) r.get("revokedAmount"));
                ok++;
            } catch (Exception e) {
                log.warn("Bulk revoke failed for log {}: {}", l.getId(), e.getMessage());
                fail++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revokedCount", ok);
        result.put("failedCount", fail);
        result.put("totalRevoked", total);
        return result;
    }

    // ════════════════ §1 DASHBOARD ════════════════

    @Transactional(readOnly = true)
    public Map<String, Object> getStats(int days) {
        int safeDays = Math.min(Math.max(days, 1), 365);
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        LocalDateTime start7 = LocalDate.now().minusDays(6).atStartOfDay();
        LocalDateTime startN = LocalDate.now().minusDays(safeDays - 1).atStartOfDay();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalToday", rewardLogRepository.sumAllSince(startOfToday));
        result.put("total7Days", rewardLogRepository.sumAllSince(start7));
        result.put("totalNDays", rewardLogRepository.sumAllSince(startN));
        result.put("usersRewardedToday", rewardLogRepository.countDistinctUsersSince(startOfToday));
        result.put("pendingCount", rewardLogRepository.countByStatus(ForumCoinRewardLog.RewardStatus.PENDING));
        result.put("cancelledCount", rewardLogRepository.countByStatus(ForumCoinRewardLog.RewardStatus.CANCELLED));

        // Biểu đồ theo ngày
        result.put("byDay", rewardLogRepository.sumByDaySince(startN).stream()
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("day", String.valueOf(row[0]));
                    m.put("total", row[1]);
                    m.put("count", row[2]);
                    return m;
                })
                .collect(Collectors.toList()));

        // Phân bổ theo action
        result.put("byAction", rewardLogRepository.sumByActionSince(startN).stream()
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("action", String.valueOf(row[0]));
                    m.put("total", row[1]);
                    m.put("count", row[2]);
                    return m;
                })
                .collect(Collectors.toList()));

        // Top user 7 ngày
        result.put("topUsers", rewardLogRepository.topUsersSince(start7, 10).stream()
                .map(row -> {
                    Integer uid = ((Number) row[0]).intValue();
                    UserBriefResponse user = getUserSafe(uid);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", uid);
                    m.put("userName", user != null ? user.getFullName() : null);
                    m.put("userAvatar", user != null ? user.getAvatar() : null);
                    m.put("total", row[1]);
                    m.put("count", row[2]);
                    return m;
                })
                .collect(Collectors.toList()));

        return result;
    }

    // ════════════════ §7 VẬN HÀNH: hàng kẹt + đối soát ════════════════

    /** Reward PENDING > 30 phút — khả năng publish RabbitMQ thất bại nhiều lần. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStuckPending() {
        return rewardLogRepository.findTop50ByStatusAndCreatedAtBeforeOrderByCreatedAtAsc(
                        ForumCoinRewardLog.RewardStatus.PENDING, LocalDateTime.now().minusMinutes(30))
                .stream().map(this::mapLog).collect(Collectors.toList());
    }

    /** Đối soát forum_coin_reward_logs vs coin_transactions bên IAM. */
    @Transactional(readOnly = true)
    public Map<String, Object> reconcile() {
        Map<String, Object> result = new LinkedHashMap<>();
        BigDecimal forumCredited = rewardLogRepository.sumEverCredited();
        BigDecimal forumRevoked = rewardLogRepository.sumRevoked();
        result.put("forumCredited", forumCredited);
        result.put("forumRevoked", forumRevoked);

        try {
            Map<String, Object> iam = iamFeignClient.getForumCoinStats();
            BigDecimal iamCredited = new BigDecimal(String.valueOf(iam.getOrDefault("creditedTotal", "0")));
            BigDecimal iamRevoked = new BigDecimal(String.valueOf(iam.getOrDefault("revokedTotal", "0")));
            result.put("iamCredited", iamCredited);
            result.put("iamRevoked", iamRevoked);
            result.put("creditDiff", forumCredited.subtract(iamCredited));
            result.put("revokeDiff", forumRevoked.subtract(iamRevoked));
            result.put("balanced", forumCredited.compareTo(iamCredited) == 0
                    && forumRevoked.compareTo(iamRevoked) == 0);
        } catch (Exception e) {
            result.put("iamError", "Không gọi được iam-service: " + e.getMessage());
            result.put("balanced", false);
        }
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private UserBriefResponse getUserSafe(Integer userId) {
        if (userId == null) return null;
        return userCache.computeIfAbsent(userId, id -> {
            try {
                return iamFeignClient.getUserById(id);
            } catch (Exception e) {
                log.warn("Could not fetch user {}: {}", id, e.getMessage());
                return null;
            }
        });
    }
}

package com.tourism.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.entity.Booking;
import com.tourism.booking.entity.GreenFundContribution;
import com.tourism.booking.entity.GreenFundLedger;
import com.tourism.booking.event.BookingEventDTO;
import com.tourism.booking.feign.IamFeignClient;
import com.tourism.booking.feign.dto.UserProfileResponse;
import com.tourism.booking.messaging.OutboxEventFactory;
import com.tourism.booking.repository.GreenFundContributionRepository;
import com.tourism.booking.repository.GreenFundLedgerRepository;
import com.tourism.booking.repository.OutboxEventRepository;
import com.tourism.booking.repository.TreePlantingBatchRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quỹ Trồng Cây Xanh (PLAN_GREEN_FUND_TRONG_CAY).
 *
 * Nguồn A — trích % từ booking thành công (doanh thu công ty, KHÔNG thu thêm của khách):
 *   fail-open + idempotent qua operationKey GF_BOOKING_{bookingCode}.
 * Nguồn B — user tự nguyện góp coin: trừ coin ĐỒNG BỘ qua IAM (deduct fail → không ghi quỹ).
 * Quy đổi: scheduler gộp quỹ tích lũy → treesPlanted (đủ costPerTree → +1 cây).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GreenFundService {

    private final GreenFundLedgerRepository ledgerRepository;
    private final GreenFundContributionRepository contributionRepository;
    private final TreePlantingBatchRepository batchRepository;
    private final IamFeignClient iamFeignClient;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    // Cache tên user cho leaderboard/dashboard (tránh Feign lặp)
    private final Map<Integer, UserProfileResponse> userCache = new ConcurrentHashMap<>();

    @Value("${greenfund.enabled:true}")
    private boolean enabled;

    /** Chi phí trồng 1 cây (VND). 1.000đ = 1 coin. */
    @Value("${greenfund.cost-per-tree:1000}")
    private long costPerTree;

    /** % trích từ giá trị booking thành công (lấy từ doanh thu). */
    @Value("${greenfund.booking-contribution-percent:0.5}")
    private double bookingContributionPercent;

    /** Số coin tối thiểu mỗi lần quyên góp. */
    @Value("${greenfund.min-donation-coin:1}")
    private int minDonationCoin;

    /** Mục tiêu số cây (progress bar dashboard). */
    @Value("${greenfund.target-trees:1000}")
    private long targetTrees;

    /** Nhãn mục tiêu hiển thị. */
    @Value("${greenfund.target-label:Mục tiêu 2026}")
    private String targetLabel;

    /** Đảm bảo dòng ledger singleton tồn tại. */
    @PostConstruct
    void initLedger() {
        try {
            if (!ledgerRepository.existsById(GreenFundLedger.SINGLETON_ID)) {
                ledgerRepository.save(GreenFundLedger.builder()
                        .id(GreenFundLedger.SINGLETON_ID)
                        .totalFundRaised(BigDecimal.ZERO)
                        .convertedFund(BigDecimal.ZERO)
                        .treesPlanted(0L)
                        .updatedAt(LocalDateTime.now())
                        .build());
                log.info("GreenFundLedger initialized");
            }
        } catch (Exception e) {
            log.warn("Could not init GreenFundLedger (sẽ thử lại khi có đóng góp): {}", e.getMessage());
        }
    }

    // ════════════════ NGUỒN B: USER GÓP COIN (đồng bộ) ════════════════

    /**
     * User góp coin trồng cây. Thứ tự an toàn:
     * validate → trừ coin IAM (throw nếu thiếu số dư) → ghi contribution + cộng ledger.
     * Idempotent qua operationKey (mỗi request 1 key UUID — duplicate HTTP retry hiếm,
     * nhưng IAM vẫn chặn double-deduct nếu key trùng).
     */
    @Transactional
    public Map<String, Object> donate(Integer userId, Integer coinAmount, boolean anonymous) {
        if (!enabled) throw new RuntimeException("Quỹ Xanh đang tạm đóng. Vui lòng quay lại sau.");
        if (userId == null) throw new RuntimeException("Bạn cần đăng nhập để góp trồng cây");
        if (coinAmount == null || coinAmount < minDonationCoin) {
            throw new RuntimeException("Số coin góp tối thiểu là " + minDonationCoin);
        }

        // Check số dư trước cho thông báo thân thiện (IAM vẫn là chốt chặn cuối)
        try {
            UserProfileResponse profile = iamFeignClient.getUserProfile(userId);
            BigDecimal balance = profile != null && profile.getCoinBalance() != null
                    ? profile.getCoinBalance() : BigDecimal.ZERO;
            if (balance.compareTo(BigDecimal.valueOf(coinAmount)) < 0) {
                throw new RuntimeException("Số dư coin không đủ (hiện có "
                        + balance.stripTrailingZeros().toPlainString() + " coin)");
            }
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Số dư coin")) throw e;
            log.warn("Không đọc được số dư user {} — để IAM tự chặn: {}", userId, e.getMessage());
        }

        String operationKey = "GF_DONATE_" + userId + "_" + UUID.randomUUID();
        BigDecimal coins = BigDecimal.valueOf(coinAmount);
        BigDecimal amountVnd = coins.multiply(BigDecimal.valueOf(1000)); // 1 coin = 1.000đ

        // 1. Ghi contribution + cộng ledger TRƯỚC (trong transaction —
        //    nếu bước trừ coin fail thì toàn bộ rollback, không lệch sổ)
        contributionRepository.save(GreenFundContribution.builder()
                .source(GreenFundContribution.Source.DONATION)
                .userId(userId)
                .coinAmount(coins)
                .amountVnd(amountVnd)
                .operationKey(operationKey)
                .anonymous(anonymous)
                .build());
        addToLedger(amountVnd);

        // 2. Trừ coin ĐỒNG BỘ — hành động ngoài (IAM) đặt CUỐI: fail → throw → rollback contribution+ledger
        try {
            iamFeignClient.deductCoins(userId, coins, operationKey);
        } catch (Exception e) {
            log.warn("Donate deduct failed: userId={}, coins={}, error={}", userId, coinAmount, e.getMessage());
            throw new RuntimeException("Trừ coin thất bại — vui lòng kiểm tra số dư và thử lại");
        }

        log.info("Green fund donation: userId={}, coins={}, vnd={}", userId, coinAmount, amountVnd);

        long myTotalTrees = getUserTrees(userId);

        // 3. Notification cảm ơn (outbox → notification-service, fail-open)
        try {
            BookingEventDTO dto = new BookingEventDTO();
            dto.setUserId(userId);
            dto.setGreenFundCoins(coins);
            dto.setGreenFundTrees(myTotalTrees);
            outboxRepository.save(OutboxEventFactory.notificationWithKey(
                    dto, "GREEN_FUND_THANKS", "GF_THANKS_" + operationKey, objectMapper));
        } catch (Exception e) {
            log.warn("Could not queue green fund thanks notification (non-critical): {}", e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("donatedCoins", coinAmount);
        result.put("donatedVnd", amountVnd);
        result.put("treesEquivalent", amountVnd.divide(BigDecimal.valueOf(costPerTree), 0, RoundingMode.DOWN));
        result.put("myTotalTrees", myTotalTrees);
        result.put("badge", badgeOf(myTotalTrees));
        return result;
    }

    // ════════════════ NGUỒN A: TRÍCH % BOOKING (fail-open) ════════════════

    /**
     * Gọi khi booking chuyển PAID. KHÔNG BAO GIỜ throw — lỗi quỹ không được làm fail booking.
     * Idempotent: GF_BOOKING_{bookingCode} — 1 booking chỉ đóng góp 1 lần.
     */
    public void contributeFromBooking(Booking booking) {
        if (!enabled || booking == null || booking.getBookingCode() == null) return;
        try {
            String operationKey = "GF_BOOKING_" + booking.getBookingCode();
            if (contributionRepository.existsByOperationKey(operationKey)) return;

            BigDecimal totalPrice = booking.getTotalPrice() != null ? booking.getTotalPrice() : BigDecimal.ZERO;
            BigDecimal amountVnd = totalPrice
                    .multiply(BigDecimal.valueOf(bookingContributionPercent))
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            if (amountVnd.signum() <= 0) return;

            contributionRepository.save(GreenFundContribution.builder()
                    .source(GreenFundContribution.Source.BOOKING)
                    .userId(booking.getUserId())
                    .bookingCode(booking.getBookingCode())
                    .amountVnd(amountVnd)
                    .operationKey(operationKey)
                    .anonymous(false)
                    .build());
            addToLedger(amountVnd);

            log.info("Green fund booking contribution: bookingCode={}, vnd={} ({}% của {})",
                    booking.getBookingCode(), amountVnd, bookingContributionPercent, totalPrice);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            log.info("Booking contribution already recorded (race): {}", booking.getBookingCode());
        } catch (Exception e) {
            // FAIL-OPEN: lỗi quỹ không được ảnh hưởng luồng booking
            log.warn("Green fund booking contribution failed (non-critical): bookingCode={}, error={}",
                    booking.getBookingCode(), e.getMessage());
        }
    }

    // ════════════════ QUY ĐỔI QUỸ → CÂY (scheduler gọi) ════════════════

    /** Gộp quỹ chưa quy đổi → tăng treesPlanted. Khóa ledger chống race. */
    @Transactional
    public void convertPendingFund() {
        if (!enabled) return;
        GreenFundLedger ledger = ledgerRepository.findSingletonForUpdate().orElse(null);
        if (ledger == null) {
            initLedger();
            return;
        }

        BigDecimal pending = ledger.getTotalFundRaised().subtract(ledger.getConvertedFund());
        long newTrees = pending.divide(BigDecimal.valueOf(costPerTree), 0, RoundingMode.DOWN).longValue();
        if (newTrees <= 0) return;

        BigDecimal convertedAmount = BigDecimal.valueOf(newTrees * costPerTree);
        ledger.setConvertedFund(ledger.getConvertedFund().add(convertedAmount));
        ledger.setTreesPlanted(ledger.getTreesPlanted() + newTrees);
        ledger.setUpdatedAt(LocalDateTime.now());
        ledgerRepository.save(ledger);

        log.info("Green fund converted: +{} trees (fund {} VND), total trees = {}",
                newTrees, convertedAmount, ledger.getTreesPlanted());
    }

    // ════════════════ API ĐỌC ════════════════

    /** Số liệu tóm tắt cho FE (modal donate + widget). */
    @Transactional(readOnly = true)
    public Map<String, Object> getSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", enabled);
        result.put("costPerTree", costPerTree);
        result.put("minDonationCoin", minDonationCoin);
        result.put("bookingContributionPercent", bookingContributionPercent);

        GreenFundLedger ledger = ledgerRepository.findById(GreenFundLedger.SINGLETON_ID).orElse(null);
        result.put("totalFundRaised", ledger != null ? ledger.getTotalFundRaised() : BigDecimal.ZERO);
        result.put("treesPlanted", ledger != null ? ledger.getTreesPlanted() : 0L);
        result.put("pendingFund", ledger != null
                ? ledger.getTotalFundRaised().subtract(ledger.getConvertedFund()) : BigDecimal.ZERO);
        result.put("totalContributors", contributionRepository.countDistinctContributors());

        Map<String, Object> bySource = new LinkedHashMap<>();
        for (Object[] row : contributionRepository.sumBySource()) {
            bySource.put(String.valueOf(row[0]), row[1]);
        }
        result.put("bySource", bySource);

        result.put("batches", batchRepository.findAllByOrderByPlantedDateDesc());
        return result;
    }

    /** Đóng góp cá nhân: tổng VND + số cây + badge + lịch sử gần đây. */
    @Transactional(readOnly = true)
    public Map<String, Object> getMyContribution(Integer userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (userId == null) {
            result.put("totalVnd", BigDecimal.ZERO);
            result.put("trees", 0L);
            result.put("donationCount", 0L);
            result.put("badge", null);
            return result;
        }
        BigDecimal totalVnd = contributionRepository.sumAmountByUser(userId);
        long trees = totalVnd.divide(BigDecimal.valueOf(costPerTree), 0, RoundingMode.DOWN).longValue();
        result.put("totalVnd", totalVnd);
        result.put("trees", trees);
        result.put("donationCount", contributionRepository.countByUserId(userId));
        result.put("badge", badgeOf(trees));
        result.put("nextBadge", nextBadgeOf(trees));
        result.put("recent", contributionRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", c.getSource().name());
                    m.put("amountVnd", c.getAmountVnd());
                    m.put("coinAmount", c.getCoinAmount());
                    m.put("bookingCode", c.getBookingCode());
                    m.put("createdAt", c.getCreatedAt());
                    return m;
                }).toList());
        return result;
    }

    /** Bảng vinh danh top người góp cây (loại ẩn danh). period = all | month. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getLeaderboard(String period, int limit) {
        LocalDateTime since = "month".equalsIgnoreCase(period)
                ? LocalDate.now().withDayOfMonth(1).atStartOfDay() : null;
        List<Map<String, Object>> result = new ArrayList<>();
        int rank = 1;
        for (Object[] row : contributionRepository.leaderboard(since, Math.min(Math.max(limit, 1), 50))) {
            Integer uid = ((Number) row[0]).intValue();
            BigDecimal total = new BigDecimal(String.valueOf(row[1]));
            long trees = total.divide(BigDecimal.valueOf(costPerTree), 0, RoundingMode.DOWN).longValue();
            UserProfileResponse user = getUserSafe(uid);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", rank++);
            m.put("userId", uid);
            m.put("userName", user != null ? user.getFullName() : "Người dùng #" + uid);
            m.put("totalVnd", total);
            m.put("trees", trees);
            m.put("contributionCount", ((Number) row[2]).longValue());
            m.put("badge", badgeOf(trees));
            result.add(m);
        }
        return result;
    }

    /** Dashboard công khai đầy đủ (PLAN §5): tổng quan + mục tiêu + leaderboard + lịch sử + đợt trồng. */
    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard() {
        Map<String, Object> result = getSummary(); // tổng quan + bySource + batches

        // Tiến trình mục tiêu
        GreenFundLedger ledger = ledgerRepository.findById(GreenFundLedger.SINGLETON_ID).orElse(null);
        long planted = ledger != null ? ledger.getTreesPlanted() : 0L;
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("label", targetLabel);
        goal.put("targetTrees", targetTrees);
        goal.put("currentTrees", planted);
        goal.put("percent", targetTrees > 0 ? Math.min(100.0, planted * 100.0 / targetTrees) : 0);
        result.put("goal", goal);

        // Top đóng góp
        result.put("leaderboard", getLeaderboard("all", 10));
        result.put("leaderboardMonth", getLeaderboard("month", 10));

        // Lịch sử gần đây (ẩn tên nếu anonymous)
        result.put("recentContributions", contributionRepository.findTop10ByOrderByCreatedAtDesc().stream()
                .map(c -> {
                    boolean anon = Boolean.TRUE.equals(c.getAnonymous());
                    UserProfileResponse user = anon || c.getUserId() == null ? null : getUserSafe(c.getUserId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("source", c.getSource().name());
                    m.put("userName", anon ? "Nhà hảo tâm ẩn danh"
                            : (user != null ? user.getFullName()
                               : (c.getSource() == GreenFundContribution.Source.BOOKING ? "Khách đặt tour" : "Người dùng")));
                    m.put("amountVnd", c.getAmountVnd());
                    m.put("trees", c.getAmountVnd().divide(BigDecimal.valueOf(costPerTree), 0, RoundingMode.DOWN));
                    m.put("createdAt", c.getCreatedAt());
                    return m;
                }).toList());

        return result;
    }

    // ════════════════ BADGE (PLAN §4) ════════════════

    /** Mốc huy hiệu theo tổng cây cá nhân. */
    public static final long[] BADGE_THRESHOLDS = {1, 5, 20, 50};

    static Map<String, Object> badgeInfo(String icon, String name, long threshold) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("icon", icon);
        m.put("name", name);
        m.put("threshold", threshold);
        return m;
    }

    /** Badge hiện tại của user theo số cây (null nếu chưa có). */
    public static Map<String, Object> badgeOf(long trees) {
        if (trees >= 50) return badgeInfo("🏆", "Đại sứ xanh", 50);
        if (trees >= 20) return badgeInfo("🌳", "Người trồng rừng", 20);
        if (trees >= 5)  return badgeInfo("🌿", "Người gieo hạt", 5);
        if (trees >= 1)  return badgeInfo("🌱", "Mầm xanh", 1);
        return null;
    }

    /** Badge kế tiếp + số cây còn thiếu (null nếu đã max). */
    public static Map<String, Object> nextBadgeOf(long trees) {
        Map<String, Object> next;
        if (trees < 1)       next = badgeInfo("🌱", "Mầm xanh", 1);
        else if (trees < 5)  next = badgeInfo("🌿", "Người gieo hạt", 5);
        else if (trees < 20) next = badgeInfo("🌳", "Người trồng rừng", 20);
        else if (trees < 50) next = badgeInfo("🏆", "Đại sứ xanh", 50);
        else return null;
        next.put("remaining", ((Number) next.get("threshold")).longValue() - trees);
        return next;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private UserProfileResponse getUserSafe(Integer userId) {
        if (userId == null) return null;
        return userCache.computeIfAbsent(userId, id -> {
            try {
                return iamFeignClient.getUserProfile(id);
            } catch (Exception e) {
                return null;
            }
        });
    }

    private long getUserTrees(Integer userId) {
        return contributionRepository.sumAmountByUser(userId)
                .divide(BigDecimal.valueOf(costPerTree), 0, RoundingMode.DOWN).longValue();
    }

    private void addToLedger(BigDecimal amountVnd) {
        int updated = ledgerRepository.addFund(amountVnd);
        if (updated == 0) {
            // Ledger chưa tồn tại → tạo rồi cộng lại
            initLedger();
            ledgerRepository.addFund(amountVnd);
        }
    }
}

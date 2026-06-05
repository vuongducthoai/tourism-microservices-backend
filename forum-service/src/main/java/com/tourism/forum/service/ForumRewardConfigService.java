package com.tourism.forum.service;

import com.tourism.forum.config.AdminContext;
import com.tourism.forum.config.ForumRewardProperties;
import com.tourism.forum.entity.ForumRewardConfig;
import com.tourism.forum.repository.ForumRewardConfigRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Config runtime + kill switch (PLAN_ADMIN_FORUM_COIN §6).
 *
 * Cách hoạt động: application.yml là giá trị MẶC ĐỊNH; admin ghi đè key nào thì
 * lưu vào bảng forum_reward_configs và áp trực tiếp lên bean ForumRewardProperties
 * (mutable singleton) → có hiệu lực ngay, KHÔNG cần restart.
 * Scheduler refresh mỗi 60s để đồng bộ lại từ DB (phòng nhiều instance / restart).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumRewardConfigService {

    private final ForumRewardConfigRepository configRepository;
    private final ForumRewardProperties props;

    /** key → (validator/parser, applier). Value lưu DB dạng string. */
    private final Map<String, ConfigKey> registry = new LinkedHashMap<>();

    private record ConfigKey(Function<String, Object> parser, BiConsumer<ForumRewardProperties, String> applier,
                             Function<ForumRewardProperties, Object> reader, String label) {}

    @PostConstruct
    void init() {
        registry.put("enabled", new ConfigKey(ForumRewardConfigService::parseBool,
                (p, v) -> p.setEnabled(parseBool(v)), ForumRewardProperties::isEnabled,
                "Bật/tắt toàn bộ thưởng coin (kill switch)"));
        registry.put("dailyCap", new ConfigKey(v -> parseDecimal(v, 0, 100),
                (p, v) -> p.setDailyCap(parseDecimal(v, 0, 100)), ForumRewardProperties::getDailyCap,
                "Trần coin/ngày/user"));
        registry.put("postAmount", new ConfigKey(v -> parseDecimal(v, 0, 50),
                (p, v) -> p.setPostAmount(parseDecimal(v, 0, 50)), ForumRewardProperties::getPostAmount,
                "Thưởng bài viết được duyệt"));
        registry.put("postDelayHours", new ConfigKey(v -> parseInt(v, 0, 168),
                (p, v) -> p.setPostDelayHours(parseInt(v, 0, 168)), ForumRewardProperties::getPostDelayHours,
                "Độ trễ thưởng bài (giờ)"));
        registry.put("maxRewardedPostsPerDay", new ConfigKey(v -> parseInt(v, 0, 50),
                (p, v) -> p.setMaxRewardedPostsPerDay(parseInt(v, 0, 50)), ForumRewardProperties::getMaxRewardedPostsPerDay,
                "Số bài được thưởng tối đa/ngày"));
        registry.put("postLikeMilestoneAmount", new ConfigKey(v -> parseDecimal(v, 0, 50),
                (p, v) -> p.setPostLikeMilestoneAmount(parseDecimal(v, 0, 50)), ForumRewardProperties::getPostLikeMilestoneAmount,
                "Thưởng mỗi mốc like bài"));
        registry.put("commentAmount", new ConfigKey(v -> parseDecimal(v, 0, 50),
                (p, v) -> p.setCommentAmount(parseDecimal(v, 0, 50)), ForumRewardProperties::getCommentAmount,
                "Thưởng comment được duyệt"));
        registry.put("minCommentLength", new ConfigKey(v -> parseInt(v, 0, 500),
                (p, v) -> p.setMinCommentLength(parseInt(v, 0, 500)), ForumRewardProperties::getMinCommentLength,
                "Độ dài comment tối thiểu"));
        registry.put("maxRewardedCommentsPerDay", new ConfigKey(v -> parseInt(v, 0, 100),
                (p, v) -> p.setMaxRewardedCommentsPerDay(parseInt(v, 0, 100)), ForumRewardProperties::getMaxRewardedCommentsPerDay,
                "Số comment được thưởng tối đa/ngày"));
        registry.put("commentLikeMilestoneAmount", new ConfigKey(v -> parseDecimal(v, 0, 50),
                (p, v) -> p.setCommentLikeMilestoneAmount(parseDecimal(v, 0, 50)), ForumRewardProperties::getCommentLikeMilestoneAmount,
                "Thưởng mỗi mốc like comment"));
        registry.put("followAmount", new ConfigKey(v -> parseDecimal(v, 0, 50),
                (p, v) -> p.setFollowAmount(parseDecimal(v, 0, 50)), ForumRewardProperties::getFollowAmount,
                "Thưởng có người follow mới"));
        registry.put("maxFollowRewardsPerDay", new ConfigKey(v -> parseInt(v, 0, 100),
                (p, v) -> p.setMaxFollowRewardsPerDay(parseInt(v, 0, 100)), ForumRewardProperties::getMaxFollowRewardsPerDay,
                "Số thưởng follow tối đa/ngày"));
        registry.put("dailyAmount", new ConfigKey(v -> parseDecimal(v, 0, 50),
                (p, v) -> p.setDailyAmount(parseDecimal(v, 0, 50)), ForumRewardProperties::getDailyAmount,
                "Thưởng hoạt động hằng ngày"));
        registry.put("streakBonus", new ConfigKey(v -> parseDecimal(v, 0, 50),
                (p, v) -> p.setStreakBonus(parseDecimal(v, 0, 50)), ForumRewardProperties::getStreakBonus,
                "Bonus chuỗi ngày"));
        registry.put("streakLength", new ConfigKey(v -> parseInt(v, 1, 30),
                (p, v) -> p.setStreakLength(parseInt(v, 1, 30)), ForumRewardProperties::getStreakLength,
                "Độ dài chuỗi nhận bonus (ngày)"));

        applyOverridesFromDb();
    }

    /** Đồng bộ lại từ DB mỗi 60s (phòng restart / nhiều instance). */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refresh() {
        try {
            applyOverridesFromDb();
        } catch (Exception e) {
            log.warn("Reward config refresh failed: {}", e.getMessage());
        }
    }

    private void applyOverridesFromDb() {
        for (ForumRewardConfig c : configRepository.findAll()) {
            ConfigKey key = registry.get(c.getConfigKey());
            if (key == null) continue;
            try {
                key.applier().accept(props, c.getConfigValue());
            } catch (Exception e) {
                log.warn("Bỏ qua config không hợp lệ {}={}: {}", c.getConfigKey(), c.getConfigValue(), e.getMessage());
            }
        }
    }

    // ════════════════ API CHO ADMIN ════════════════

    /** Snapshot config hiệu lực + key nào đang bị ghi đè. */
    @Transactional(readOnly = true)
    public Map<String, Object> snapshot() {
        Set<String> overridden = new HashSet<>();
        Map<String, Object> overriddenMeta = new LinkedHashMap<>();
        for (ForumRewardConfig c : configRepository.findAll()) {
            overridden.add(c.getConfigKey());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("updatedBy", c.getUpdatedBy());
            meta.put("updatedAt", c.getUpdatedAt());
            overriddenMeta.put(c.getConfigKey(), meta);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        registry.forEach((key, def) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("label", def.label());
            m.put("value", def.reader().apply(props));
            m.put("overridden", overridden.contains(key));
            items.add(m);
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("overriddenMeta", overriddenMeta);
        result.put("enabled", props.isEnabled());
        return result;
    }

    /** Cập nhật nhiều key cùng lúc. Validate trước, sai key/giá trị → throw, không áp gì cả. */
    @Transactional
    public void update(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) throw new RuntimeException("Không có thay đổi nào");

        // 1. Validate toàn bộ trước
        for (Map.Entry<String, String> e : changes.entrySet()) {
            ConfigKey key = registry.get(e.getKey());
            if (key == null) throw new RuntimeException("Key không hợp lệ: " + e.getKey());
            try {
                key.parser().apply(e.getValue());
            } catch (Exception ex) {
                throw new RuntimeException("Giá trị không hợp lệ cho " + e.getKey() + ": " + e.getValue());
            }
        }

        // 2. Lưu DB + áp lên bean
        Integer adminId = AdminContext.currentUserId();
        for (Map.Entry<String, String> e : changes.entrySet()) {
            ForumRewardConfig row = configRepository.findByConfigKey(e.getKey())
                    .orElseGet(() -> ForumRewardConfig.builder().configKey(e.getKey()).build());
            row.setConfigValue(e.getValue());
            row.setUpdatedBy(adminId);
            configRepository.save(row);
            registry.get(e.getKey()).applier().accept(props, e.getValue());
        }
        log.info("Admin {} updated reward config: {}", adminId, changes.keySet());
    }

    /** Xóa ghi đè 1 key → quay về giá trị mặc định trong yml? KHÔNG thể đọc lại yml runtime
     *  một cách đơn giản, nên reset = admin tự đặt lại giá trị mong muốn. (Giữ API đơn giản.) */

    /** Kill switch: tắt/bật khẩn cấp toàn bộ thưởng — có hiệu lực NGAY. */
    @Transactional
    public void killSwitch(boolean enabled) {
        update(Map.of("enabled", String.valueOf(enabled)));
        log.warn("KILL SWITCH: forum coin reward {} by admin {}",
                enabled ? "ENABLED" : "DISABLED", AdminContext.currentUserId());
    }

    // ── Parsers ──────────────────────────────────────────────────────────

    private static boolean parseBool(String v) {
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return Boolean.parseBoolean(v);
        throw new IllegalArgumentException("Phải là true/false");
    }

    private static BigDecimal parseDecimal(String v, double min, double max) {
        BigDecimal d = new BigDecimal(v.trim());
        if (d.doubleValue() < min || d.doubleValue() > max) {
            throw new IllegalArgumentException("Phải trong khoảng " + min + " - " + max);
        }
        return d;
    }

    private static int parseInt(String v, int min, int max) {
        int i = Integer.parseInt(v.trim());
        if (i < min || i > max) {
            throw new IllegalArgumentException("Phải trong khoảng " + min + " - " + max);
        }
        return i;
    }
}

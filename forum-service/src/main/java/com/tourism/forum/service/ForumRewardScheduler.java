package com.tourism.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler thưởng coin forum (PLAN_FORUM_COIN_REWARD §4):
 * 1. Quét PostRewardSchedule — bài đủ 24h vẫn PUBLISHED → phát thưởng.
 * 2. Retry các reward PENDING (publish RabbitMQ thất bại) — mini-outbox.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForumRewardScheduler {

    private final ForumRewardService rewardService;
    private final ForumRewardAlertService alertService;

    /** Chạy mỗi 5 phút: xử lý bài viết đủ hạn 24h. */
    @Scheduled(fixedDelayString = "${forum.reward.schedule-interval-ms:300000}", initialDelay = 60_000)
    public void processDuePostRewards() {
        try {
            rewardService.processDuePostRewards();
        } catch (Exception e) {
            log.warn("processDuePostRewards failed: {}", e.getMessage());
        }
    }

    /** Chạy mỗi 5 phút: gửi lại các reward PENDING. */
    @Scheduled(fixedDelayString = "${forum.reward.retry-interval-ms:300000}", initialDelay = 90_000)
    public void retryPendingRewards() {
        try {
            rewardService.retryPendingRewards();
        } catch (Exception e) {
            log.warn("retryPendingRewards failed: {}", e.getMessage());
        }
    }

    /** Chạy mỗi giờ: quét rule anti-fraud sinh cảnh báo (PLAN_ADMIN_FORUM_COIN §5). */
    @Scheduled(fixedDelayString = "${forum.reward.alert-interval-ms:3600000}", initialDelay = 120_000)
    public void scanRewardAlerts() {
        try {
            alertService.runRules();
        } catch (Exception e) {
            log.warn("scanRewardAlerts failed: {}", e.getMessage());
        }
    }
}

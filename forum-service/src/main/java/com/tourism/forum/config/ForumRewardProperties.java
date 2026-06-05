package com.tourism.forum.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cấu hình chính sách thưởng coin forum (PLAN_FORUM_COIN_REWARD §2).
 * Mọi con số đều chỉnh được qua application.yml mà không sửa code.
 */
@Data
@Component
@ConfigurationProperties(prefix = "forum.reward")
public class ForumRewardProperties {

    /** Bật/tắt toàn bộ tính năng thưởng coin forum. */
    private boolean enabled = false;

    /** Trần coin/ngày toàn cục: vượt → im lặng không cộng nữa. */
    private BigDecimal dailyCap = new BigDecimal("6");

    // ── Bài viết (thưởng có độ trễ) ──
    private BigDecimal postAmount = new BigDecimal("2.0");
    private int postDelayHours = 24;
    private int maxRewardedPostsPerDay = 3;

    // ── Mốc like bài viết ──
    private List<Integer> postLikeMilestones = List.of(5, 20, 50, 100);
    private BigDecimal postLikeMilestoneAmount = new BigDecimal("0.5");

    // ── Comment ──
    private BigDecimal commentAmount = new BigDecimal("0.2");
    private int minCommentLength = 15;
    private int maxRewardedCommentsPerDay = 10;

    // ── Mốc like comment ──
    private List<Integer> commentLikeMilestones = List.of(5, 15);
    private BigDecimal commentLikeMilestoneAmount = new BigDecimal("0.2");

    // ── Follow ──
    private BigDecimal followAmount = new BigDecimal("0.3");
    private int maxFollowRewardsPerDay = 10;

    // ── Daily streak ──
    private BigDecimal dailyAmount = new BigDecimal("0.5");
    private BigDecimal streakBonus = new BigDecimal("2.0");
    private int streakLength = 7;
}

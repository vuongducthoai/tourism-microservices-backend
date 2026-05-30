package com.tourism.forum.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;

/**
 * Chống spam comment & bài viết bằng Redis.
 * 3 tầng: cooldown (chống burst) + quota/ngày + chống duplicate content.
 * Fail-open: nếu Redis lỗi → cho qua, không chặn user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumRateLimitService {

    private final StringRedisTemplate redis;

    @Value("${forum.ratelimit.comment-cooldown-sec:15}")
    private long commentCooldown;

    @Value("${forum.ratelimit.post-cooldown-sec:60}")
    private long postCooldown;

    @Value("${forum.ratelimit.max-comments-per-day:30}")
    private int maxCommentsPerDay;

    @Value("${forum.ratelimit.max-posts-per-day:5}")
    private int maxPostsPerDay;

    @Value("${forum.ratelimit.enabled:true}")
    private boolean enabled;

    /** Kiểm tra giới hạn comment. Throw RuntimeException nếu vi phạm. */
    public void checkCommentLimit(Integer userId) {
        if (!enabled || userId == null) return;
        try {
            // Tầng 1: cooldown
            String cdKey = "forum:cd:comment:" + userId;
            Boolean ok = redis.opsForValue().setIfAbsent(cdKey, "1", Duration.ofSeconds(commentCooldown));
            if (Boolean.FALSE.equals(ok)) {
                throw new RateLimitException("Bạn bình luận quá nhanh. Vui lòng đợi vài giây rồi thử lại.");
            }
            // Tầng 2: quota ngày
            String dayKey = "forum:daily:comment:" + userId + ":" + LocalDate.now();
            Long count = redis.opsForValue().increment(dayKey);
            if (count != null && count == 1L) {
                redis.expire(dayKey, Duration.ofDays(1));
            }
            if (count != null && count > maxCommentsPerDay) {
                throw new RateLimitException(
                    "Bạn đã đạt giới hạn " + maxCommentsPerDay + " bình luận/ngày. Vui lòng quay lại vào ngày mai.");
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Rate-limit Redis error (fail-open) for user {}: {}", userId, e.getMessage());
        }
    }

    /** Kiểm tra giới hạn đăng bài. Throw nếu vi phạm. */
    public void checkPostLimit(Integer userId) {
        if (!enabled || userId == null) return;
        try {
            String cdKey = "forum:cd:post:" + userId;
            Boolean ok = redis.opsForValue().setIfAbsent(cdKey, "1", Duration.ofSeconds(postCooldown));
            if (Boolean.FALSE.equals(ok)) {
                throw new RateLimitException("Bạn đăng bài quá nhanh. Vui lòng đợi 1 phút rồi thử lại.");
            }
            String dayKey = "forum:daily:post:" + userId + ":" + LocalDate.now();
            Long count = redis.opsForValue().increment(dayKey);
            if (count != null && count == 1L) {
                redis.expire(dayKey, Duration.ofDays(1));
            }
            if (count != null && count > maxPostsPerDay) {
                throw new RateLimitException(
                    "Bạn đã đạt giới hạn " + maxPostsPerDay + " bài viết/ngày. Vui lòng quay lại vào ngày mai.");
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Rate-limit Redis error (fail-open) for user {}: {}", userId, e.getMessage());
        }
    }

    /** Chống gửi trùng nội dung trong 5 phút. */
    public void checkDuplicate(Integer userId, String content) {
        if (!enabled || userId == null || content == null || content.isBlank()) return;
        try {
            String hash = Integer.toHexString(content.trim().toLowerCase().hashCode());
            String key = "forum:dup:" + userId + ":" + hash;
            Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofMinutes(5));
            if (Boolean.FALSE.equals(ok)) {
                throw new RateLimitException("Bạn vừa gửi nội dung này rồi. Vui lòng không gửi trùng lặp.");
            }
        } catch (RateLimitException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Duplicate-check Redis error (fail-open) for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Lấy số lượt còn lại trong ngày để FE hiển thị minh bạch.
     * Không tăng counter — chỉ đọc.
     */
    public QuotaStatus getQuotaStatus(Integer userId) {
        int commentsUsed = readDailyCount("forum:daily:comment:" + userId + ":" + LocalDate.now());
        int postsUsed = readDailyCount("forum:daily:post:" + userId + ":" + LocalDate.now());
        return new QuotaStatus(
            maxCommentsPerDay,
            Math.max(0, maxCommentsPerDay - commentsUsed),
            maxPostsPerDay,
            Math.max(0, maxPostsPerDay - postsUsed)
        );
    }

    private int readDailyCount(String key) {
        try {
            String val = redis.opsForValue().get(key);
            return val == null ? 0 : Integer.parseInt(val);
        } catch (Exception e) {
            return 0;
        }
    }

    /** DTO trả về cho FE. */
    public record QuotaStatus(
        int maxCommentsPerDay,
        int remainingComments,
        int maxPostsPerDay,
        int remainingPosts
    ) {}

    /** Exception riêng để controller map sang HTTP 429. */
    public static class RateLimitException extends RuntimeException {
        public RateLimitException(String message) { super(message); }
    }
}

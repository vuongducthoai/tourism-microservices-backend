package com.tourism.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Chống spam form tư vấn:
 *  - cùng SĐT: tối đa 3 lần / giờ
 *  - cùng IP: tối đa 10 lần / giờ
 * Fail-open: nếu Redis lỗi → cho qua, không chặn user thật.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultationRateLimitService {

    private static final int MAX_PER_PHONE_HOUR = 3;
    private static final int MAX_PER_IP_HOUR    = 10;

    private final StringRedisTemplate redis;

    public void check(String phone, String ip) {
        if (phone != null && !phone.isBlank()) {
            String key = "consult:phone:" + phone.trim();
            long count = increment(key);
            if (count > MAX_PER_PHONE_HOUR) {
                throw new ConsultationRateLimitException(
                        "Bạn đã gửi quá nhiều yêu cầu trong 1 giờ. Vui lòng thử lại sau.");
            }
        }
        if (ip != null && !ip.isBlank()) {
            String key = "consult:ip:" + ip.trim();
            long count = increment(key);
            if (count > MAX_PER_IP_HOUR) {
                throw new ConsultationRateLimitException(
                        "Vui lòng thử lại sau 1 giờ.");
            }
        }
    }

    private long increment(String key) {
        try {
            Long val = redis.opsForValue().increment(key);
            if (val != null && val == 1L) {
                redis.expire(key, Duration.ofHours(1));
            }
            return val == null ? 0L : val;
        } catch (Exception e) {
            log.warn("Redis rate-limit error (fail-open): {}", e.getMessage());
            return 0L;
        }
    }

    public static class ConsultationRateLimitException extends RuntimeException {
        public ConsultationRateLimitException(String msg) { super(msg); }
    }
}

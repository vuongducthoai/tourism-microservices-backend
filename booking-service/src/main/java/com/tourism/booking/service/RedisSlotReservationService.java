package com.tourism.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Giữ chỗ tuyến đầu bằng Redis (atomic Lua) — GIẢM TẢI cho database.
 *
 * Vai trò: là "cổng chặn" đặt trước tầng DB. Phần lớn request khi HẾT CHỖ sẽ bị
 * từ chối ngay tại Redis (rất nhanh, không chạm DB), giảm tranh chấp trên "hot row".
 * DB vẫn là NGUỒN SỰ THẬT cuối cùng (vẫn trừ chỗ atomic ở tour-catalog).
 *
 * An toàn:
 *  - Lua đảm bảo kiểm-tra-và-trừ là NGUYÊN TỬ (không lo race trên Redis).
 *  - Key tự nạp lại (seed) từ số chỗ DB nếu chưa tồn tại, và có TTL để tự đồng bộ lại (self-heal).
 *  - FAIL-OPEN: nếu Redis lỗi/không có → bỏ qua cổng Redis, để DB quyết định (không chặn user thật).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSlotReservationService {

    private final StringRedisTemplate redis;

    /** Key tự hết hạn để định kỳ nạp lại số chỗ từ DB (chống lệch dữ liệu). */
    private static final long TTL_SECONDS = 3600;

    // KEYS[1] = key giữ chỗ; ARGV[1] = số chỗ cần; ARGV[2] = số chỗ DB (seed nếu key chưa có); ARGV[3] = TTL
    private static final String RESERVE_LUA =
            "if redis.call('EXISTS', KEYS[1]) == 0 then " +
            "  redis.call('SET', KEYS[1], ARGV[2]) " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[3]) " +
            "end " +
            "local cur = tonumber(redis.call('GET', KEYS[1])) " +
            "if cur >= tonumber(ARGV[1]) then " +
            "  redis.call('DECRBY', KEYS[1], ARGV[1]) " +
            "  return 1 " +
            "else return 0 end";

    private static final DefaultRedisScript<Long> RESERVE_SCRIPT =
            new DefaultRedisScript<>(RESERVE_LUA, Long.class);

    private String key(Integer departureId) {
        return "slots:departure:" + departureId;
    }

    /**
     * Giữ chỗ nguyên tử trên Redis.
     * @return TRUE = còn đủ chỗ (đã trừ); FALSE = hết chỗ (từ chối nhanh); NULL = Redis lỗi (bỏ qua cổng, để DB xử lý).
     */
    public Boolean tryReserve(Integer departureId, int count, int dbAvailable) {
        if (departureId == null || count <= 0) return null;
        try {
            Long r = redis.execute(
                    RESERVE_SCRIPT,
                    List.of(key(departureId)),
                    String.valueOf(count),
                    String.valueOf(Math.max(dbAvailable, 0)),
                    String.valueOf(TTL_SECONDS));
            return r != null && r == 1L;
        } catch (Exception e) {
            log.warn("Redis giu cho loi (fail-open, dung DB): {}", e.getMessage());
            return null;
        }
    }

    /** Trả chỗ lại vào Redis (khi hủy đơn / bù trừ khi tạo đơn thất bại). */
    public void release(Integer departureId, int count) {
        if (departureId == null || count <= 0) return;
        try {
            redis.opsForValue().increment(key(departureId), count);
        } catch (Exception e) {
            log.warn("Redis tra cho loi: {}", e.getMessage());
        }
    }
}

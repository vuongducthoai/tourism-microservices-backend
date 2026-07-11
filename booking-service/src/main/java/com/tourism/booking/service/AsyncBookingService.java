package com.tourism.booking.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.booking.dto.request.CreateBookingRequest;
import com.tourism.booking.dto.response.BookingRequestResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Đặt tour BẤT ĐỒNG BỘ qua hàng đợi Kafka để SAN TẢI:
 *  - Yêu cầu đặt tour được đẩy vào topic Kafka (key = departureId → cùng 1 lịch vào cùng partition
 *    → được xử lý TUẦN TỰ, tránh dồn tải tức thời lên hệ thống).
 *  - Kết quả xử lý lưu tạm ở Redis, FE poll theo requestId cho tới khi xong.
 * Vẫn giữ endpoint đồng bộ /bookings/create làm phương án dự phòng.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncBookingService {

    public static final String TOPIC = "booking-requests";
    private static final long RESULT_TTL_SECONDS = 900; // 15 phút

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private String key(String requestId) {
        return "bookreq:result:" + requestId;
    }

    /** Nhận yêu cầu, đẩy vào Kafka, trả về trạng thái PROCESSING kèm requestId. */
    public BookingRequestResult submit(CreateBookingRequest request) {
        String requestId = (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank())
                ? request.getIdempotencyKey()
                : UUID.randomUUID().toString();
        request.setIdempotencyKey(requestId);

        // Nếu đã có kết quả (double-submit) → trả luôn, không đẩy lại
        BookingRequestResult existing = getStatus(requestId);
        if (existing != null && !"PROCESSING".equals(existing.getStatus())) {
            return existing;
        }

        BookingRequestResult pending = new BookingRequestResult(requestId, "PROCESSING", null, null, null);
        store(pending);

        // key = departureId để cùng 1 lịch vào cùng partition (xử lý tuần tự theo lịch)
        kafkaTemplate.send(TOPIC, String.valueOf(request.getDepartureId()), request);
        log.info("Da day yeu cau dat tour vao Kafka (requestId={}, departureId={})",
                requestId, request.getDepartureId());
        return pending;
    }

    /** FE poll trạng thái theo requestId. */
    public BookingRequestResult getStatus(String requestId) {
        try {
            String json = redis.opsForValue().get(key(requestId));
            if (json == null) return null;
            return objectMapper.readValue(json, BookingRequestResult.class);
        } catch (Exception e) {
            log.warn("Doc trang thai booking async loi (id={}): {}", requestId, e.getMessage());
            return null;
        }
    }

    /** Consumer gọi để lưu kết quả sau khi xử lý xong. */
    public void store(BookingRequestResult result) {
        try {
            redis.opsForValue().set(key(result.getRequestId()),
                    objectMapper.writeValueAsString(result),
                    Duration.ofSeconds(RESULT_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("Luu ket qua booking async loi (id={}): {}", result.getRequestId(), e.getMessage());
        }
    }
}

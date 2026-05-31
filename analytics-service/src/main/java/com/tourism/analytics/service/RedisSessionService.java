package com.tourism.analytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.analytics.dto.chatbot.ConversationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * RedisSessionService — lưu/đọc ConversationState cho chatbot stateful.
 * Key schema: "chatbot:session:{sessionId}"
 * TTL: 30 phút (reset mỗi lần có activity)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisSessionService {

    private static final String KEY_PREFIX = "chatbot:session:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    public ConversationState getOrCreate(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        log.debug("🔎 Redis GET session key={}", key);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                ConversationState state = objectMapper.readValue(json, ConversationState.class);
                log.info("🧠 Redis HIT sessionId={} stage={} — dùng Redis để giữ ngữ cảnh hội thoại đa lượt (booking/search) giữa các request", sessionId, state.getStage());
                log.debug("🧾 Redis session details key={} recentTurns={}", key,
                        state.getRecentTurns() == null ? 0 : state.getRecentTurns().size());
                return state;
            }
            log.info("🆕 Redis MISS sessionId={} — tạo state mới. Redis được dùng để lưu trạng thái hội thoại tạm thời thay vì giữ trong RAM của 1 instance", sessionId);
        } catch (Exception e) {
            log.warn("⚠️ Redis read failed for sessionId={} key={} errorType={} message={} — fallback sang state mới, hệ thống vẫn trả lời nhưng có thể mất ngữ cảnh đa lượt",
                    sessionId, key, e.getClass().getSimpleName(), e.getMessage());
        }
        return ConversationState.builder().build();
    }

    public void save(String sessionId, ConversationState state) {
        String key = KEY_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
            log.info("💾 Redis SAVE sessionId={} stage={} — refresh TTL={} phút để giữ ngữ cảnh khi user còn hoạt động",
                    sessionId, state.getStage(), SESSION_TTL.toMinutes());
            log.debug("🧾 Redis save details key={} previousStage={} recentTurns={}",
                    key, state.getPreviousStage(), state.getRecentTurns() == null ? 0 : state.getRecentTurns().size());
        } catch (Exception e) {
            log.warn("⚠️ Redis write failed for sessionId={} key={} errorType={} message={} — không lưu được state mới, request hiện tại vẫn xử lý nhưng bước sau có thể quên ngữ cảnh",
                    sessionId, key, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    public void delete(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        Boolean deleted = redisTemplate.delete(key);
        if (Boolean.TRUE.equals(deleted)) {
            log.info("🗑️ Redis DELETE sessionId={} key={} — đã xóa state hội thoại, phiên tiếp theo sẽ bắt đầu như mới", sessionId, key);
        } else {
            log.info("🗑️ Redis DELETE sessionId={} key={} — không tìm thấy key để xóa (có thể đã hết TTL)", sessionId, key);
        }
    }
}

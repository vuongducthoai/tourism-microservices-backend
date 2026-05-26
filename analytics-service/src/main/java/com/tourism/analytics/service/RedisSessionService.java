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
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                return objectMapper.readValue(json, ConversationState.class);
            }
        } catch (Exception e) {
            log.warn("⚠️ Redis read failed for session {}: {}", sessionId, e.getMessage());
        }
        return ConversationState.builder().build();
    }

    public void save(String sessionId, ConversationState state) {
        String key = KEY_PREFIX + sessionId;
        try {
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            log.warn("⚠️ Redis write failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    public void delete(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }
}

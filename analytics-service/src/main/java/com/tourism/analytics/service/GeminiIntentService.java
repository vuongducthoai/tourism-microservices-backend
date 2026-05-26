package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import com.tourism.analytics.dto.chatbot.IntentResult.Intent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GeminiIntentService — gọi Gemini để phân loại intent khi fast-path không đủ.
 *
 * Chỉ được gọi từ IntentRouter khi có recentTurns (có context hội thoại).
 * Trả về IntentResult với intent và entity được phân tích từ JSON response của Gemini.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiIntentService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.generation.model:gemini-2.0-flash-lite}")
    private String generationModel;

    private final RestTemplate restTemplate;
    private final Gson gson = new Gson();

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    /**
     * Phân loại intent bằng Gemini.
     * @param message  tin nhắn user
     * @param state    trạng thái hội thoại hiện tại (để cung cấp context)
     * @return IntentResult hoặc null nếu không phân loại được
     */
    public IntentResult classify(String message, ConversationState state) {
        String prompt = buildClassificationPrompt(message, state);

        Map<String, Object> genConfig = new HashMap<>();
        genConfig.put("temperature",     0.0);
        genConfig.put("maxOutputTokens", 300);

        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
        ));
        body.put("generationConfig", genConfig);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);

        try {
            String url = GEMINI_URL + generationModel + ":generateContent?key=" + geminiApiKey;
            ResponseEntity<Map> response = restTemplate.postForEntity(url, req, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<Map<String, Object>> candidates =
                        (List<Map<String, Object>>) response.getBody().get("candidates");
                if (candidates != null && !candidates.isEmpty()) {
                    Map<String, Object> content =
                            (Map<String, Object>) candidates.get(0).get("content");
                    List<Map<String, Object>> parts =
                            (List<Map<String, Object>>) content.get("parts");
                    if (parts != null && !parts.isEmpty()) {
                        String text = parts.get(0).get("text").toString().trim();
                        return parseGeminiIntentResponse(text);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ GeminiIntentService error: {}", e.getMessage());
        }
        return null;
    }

    private String buildClassificationPrompt(String message, ConversationState state) {
        String turnHistory = state.getRecentTurns().stream()
                .map(t -> t.getRole() + ": " + truncate(t.getContent(), 80))
                .collect(Collectors.joining("\n"));

        return String.format("""
                Phân loại intent của tin nhắn user sau trong ngữ cảnh hội thoại du lịch.
                
                LỊCH SỬ HỘI THOẠI GẦN NHẤT:
                %s
                
                TIN NHẮN MỚI NHẤT: "%s"
                
                Trả lời JSON duy nhất (không markdown, không giải thích):
                {
                  "intent": "<INTENT>",
                  "destination": "<string hoặc null>",
                  "travelMonth": "<string hoặc null>",
                  "adultCount": <number hoặc null>,
                  "confidence": <0.0-1.0>
                }
                
                INTENT hợp lệ (chọn 1):
                GREETING, CANCEL, RESUME_BOOKING, TOUR_SEARCH, CHANGE_SEARCH, START_LOCATION_SEARCH,
                BOOKING_FLOW, BOOKING_LOOKUP, ASK_DETAIL, ASK_SLOT, ASK_PRICE,
                ASK_CHILD_PRICE, ASK_DEPARTURE_DATE, ASK_ITINERARY, ASK_POLICY,
                ASK_DISCOUNT, ASK_COUPON, PAYMENT_HELP, GENERAL_TRAVEL_ADVICE,
                SYSTEM_HELP, UNKNOWN
                """, turnHistory, message);
    }

    @SuppressWarnings("unchecked")
    private IntentResult parseGeminiIntentResponse(String text) {
        // Strip markdown code fences if present
        String json = text.replaceAll("(?s)```json\\s*", "").replaceAll("(?s)```\\s*", "").trim();

        try {
            Map<String, Object> result = gson.fromJson(json, Map.class);
            String intentStr = getString(result, "intent");
            Intent intent;
            try {
                intent = Intent.valueOf(intentStr.toUpperCase());
            } catch (Exception e) {
                intent = Intent.UNKNOWN;
            }

            double confidence = result.containsKey("confidence")
                    ? ((Number) result.get("confidence")).doubleValue() : 0.5;

            return IntentResult.builder()
                    .intent(intent)
                    .destination(getString(result, "destination"))
                    .travelMonth(getString(result, "travelMonth"))
                    .adultCount(result.containsKey("adultCount") && result.get("adultCount") != null
                            ? ((Number) result.get("adultCount")).intValue() : null)
                    .rawSource("gemini")
                    .confidence(confidence)
                    .build();
        } catch (Exception e) {
            log.warn("⚠️ Failed to parse Gemini intent JSON: {}", e.getMessage());
            return null;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null && !"null".equals(v.toString()) ? v.toString() : null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}

package com.tourism.analytics.service;

import com.google.gson.Gson;
import com.tourism.analytics.dto.chatbot.ConversationState;
import com.tourism.analytics.dto.chatbot.IntentResult;
import com.tourism.analytics.dto.chatbot.IntentResult.Intent;
import com.tourism.analytics.dto.chatbot.IntentResult.RetrievalTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI fallback classifier for route groups only.
 *
 * Business handlers still validate data deterministically. Gemini only helps
 * choose the broad route and, for tour questions, the retrieval task.
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

    public IntentResult classify(String message, ConversationState state) {
        String prompt = buildClassificationPrompt(message, state);

        Map<String, Object> genConfig = new HashMap<>();
        genConfig.put("temperature", 0.0);
        genConfig.put("maxOutputTokens", 300);
        genConfig.put("responseMimeType", "application/json");

        Map<String, Object> body = new HashMap<>();
        body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
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
            log.warn("GeminiIntentService error: {}", e.getMessage());
        }
        return null;
    }

    private String buildClassificationPrompt(String message, ConversationState state) {
        String turnHistory = state.getRecentTurns() == null ? "" : state.getRecentTurns().stream()
                .map(t -> t.getRole() + ": " + truncate(t.getContent(), 80))
                .collect(Collectors.joining("\n"));

        return String.format("""
                Phan loai tin nhan chatbot du lich theo ROUTE_GROUP lon.

                Lich su gan nhat:
                %s

                Tin moi nhat: "%s"

                Tra loi JSON duy nhat:
                {
                  "intent": "GREETING|CANCEL|RESUME_BOOKING|TRANSACTION_FLOW|BOOKING_LOOKUP_PAYMENT|BOOKING_CANCEL_HELP|TOUR_RETRIEVAL|GENERAL_RAG|UNKNOWN",
                  "retrievalTask": "SEARCH|DETAIL|SLOT|PRICE|CHILD_PRICE|DEPARTURE_DATE|ITINERARY|POLICY|DISCOUNT|COUPON|ADVICE|null",
                  "destination": null,
                  "startLocation": null,
                  "travelMonth": null,
                  "adultCount": null,
                  "confidence": 0.0
                }

                Rules:
                - Dat tour, chon 1/2/3, chon ngay, nhap hanh khach/contact/email/xac nhan -> TRANSACTION_FLOW.
                - Tra cuu booking, ma BK, thanh toan -> BOOKING_LOOKUP_PAYMENT.
                - Huy booking/tour da dat theo ma BK -> BOOKING_CANCEL_HELP.
                - Hoi tour/gia/slot/ngay/lich trinh/chi tiet/khuyen mai -> TOUR_RETRIEVAL.
                - Tu van chung/chinh sach/hanh ly/kinh nghiem -> GENERAL_RAG.
                """, turnHistory, message);
    }

    @SuppressWarnings("unchecked")
    private IntentResult parseGeminiIntentResponse(String text) {
        String json = text.replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();

        try {
            Map<String, Object> result = gson.fromJson(json, Map.class);
            String rawIntent = getString(result, "intent");
            Intent intent = parseRouteGroup(rawIntent);
            RetrievalTask task = parseRetrievalTask(getString(result, "retrievalTask"), rawIntent);

            double confidence = result.containsKey("confidence") && result.get("confidence") instanceof Number n
                    ? n.doubleValue()
                    : 0.5;

            return IntentResult.builder()
                    .intent(intent)
                    .retrievalTask(task)
                    .destination(getString(result, "destination"))
                    .startLocation(getString(result, "startLocation"))
                    .travelMonth(getString(result, "travelMonth"))
                    .adultCount(result.containsKey("adultCount") && result.get("adultCount") instanceof Number n
                            ? n.intValue()
                            : null)
                    .rawSource("gemini")
                    .confidence(confidence)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse Gemini intent JSON: {}", e.getMessage());
            return null;
        }
    }

    private Intent parseRouteGroup(String rawIntent) {
        if (rawIntent == null) return Intent.UNKNOWN;
        String value = rawIntent.toUpperCase();
        try {
            return Intent.valueOf(value);
        } catch (Exception ignored) {
            return switch (value) {
                case "TOUR_SEARCH", "CHANGE_SEARCH", "START_LOCATION_SEARCH",
                     "ASK_DETAIL", "ASK_SLOT", "ASK_PRICE", "ASK_CHILD_PRICE",
                     "ASK_DEPARTURE_DATE", "ASK_ITINERARY", "ASK_POLICY",
                     "ASK_DISCOUNT", "ASK_COUPON" -> Intent.TOUR_RETRIEVAL;
                case "BOOKING_FLOW" -> Intent.TRANSACTION_FLOW;
                case "BOOKING_LOOKUP", "PAYMENT_HELP" -> Intent.BOOKING_LOOKUP_PAYMENT;
                case "GENERAL_TRAVEL_ADVICE", "SYSTEM_HELP" -> Intent.GENERAL_RAG;
                default -> Intent.UNKNOWN;
            };
        }
    }

    private RetrievalTask parseRetrievalTask(String rawTask, String rawIntent) {
        String value = rawTask != null ? rawTask.toUpperCase() : "";
        if (!value.isBlank() && !"NULL".equals(value)) {
            try {
                return RetrievalTask.valueOf(value);
            } catch (Exception ignored) {
            }
        }
        String intent = rawIntent == null ? "" : rawIntent.toUpperCase();
        return switch (intent) {
            case "ASK_DETAIL" -> RetrievalTask.DETAIL;
            case "ASK_SLOT" -> RetrievalTask.SLOT;
            case "ASK_PRICE" -> RetrievalTask.PRICE;
            case "ASK_CHILD_PRICE" -> RetrievalTask.CHILD_PRICE;
            case "ASK_DEPARTURE_DATE" -> RetrievalTask.DEPARTURE_DATE;
            case "ASK_ITINERARY" -> RetrievalTask.ITINERARY;
            case "ASK_POLICY" -> RetrievalTask.POLICY;
            case "ASK_DISCOUNT" -> RetrievalTask.DISCOUNT;
            case "ASK_COUPON" -> RetrievalTask.COUPON;
            case "TOUR_SEARCH", "CHANGE_SEARCH", "START_LOCATION_SEARCH" -> RetrievalTask.SEARCH;
            default -> null;
        };
    }

    private String getString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null && !"null".equalsIgnoreCase(v.toString()) ? v.toString() : null;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

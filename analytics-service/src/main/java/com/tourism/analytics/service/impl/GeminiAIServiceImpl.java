package com.tourism.analytics.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tourism.analytics.dto.dashboard.DashboardStatsDTO;
import com.tourism.analytics.service.GeminiAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * GeminiAIServiceImpl — port từ monolith Tourism_Backend.
 * Gọi Gemini 2.0 Flash API để tạo phân tích AI cho Dashboard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiAIServiceImpl implements GeminiAIService {

    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key:}")
    private String geminiApiKey;

    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public String generateDashboardSummary(String context) {
        String prompt = String.format(
                "Bạn là chuyên gia phân tích dữ liệu du lịch. Dựa trên dữ liệu sau:\n%s\n\n" +
                        "Hãy viết tóm tắt ngắn gọn (4-5 câu) về tình hình kinh doanh hiện tại. " +
                        "Giọng văn tích cực, chuyên nghiệp. Trả lời bằng tiếng Việt Nam.",
                context
        );
        return callGeminiAPI(prompt);
    }

    @Override
    public List<DashboardStatsDTO.Insight> generateInsights(String context) {
        String prompt = String.format(
                "Dựa trên dữ liệu: %s\n" +
                        "Hãy đưa ra 3-5 insight quan trọng dưới dạng JSON Array. " +
                        "Cấu trúc mỗi object: { \"title\": \"...\", \"description\": \"...\", \"type\": \"POSITIVE/NEUTRAL/NEGATIVE\", \"priority\": 1-5 }. " +
                        "QUAN TRỌNG: Chỉ trả về JSON thuần túy, không dùng Markdown block. Trả lời bằng tiếng Việt Nam.",
                context
        );
        String jsonResponse = callGeminiAPI(prompt);
        return parseResponse(jsonResponse, new TypeReference<List<DashboardStatsDTO.Insight>>() {});
    }

    @Override
    public List<DashboardStatsDTO.Prediction> generatePredictions(String context) {
        String prompt = String.format(
                "Dựa trên dữ liệu: %s\n" +
                        "Hãy dự đoán 2-3 xu hướng sắp tới (1-3 tháng) dưới dạng JSON Array. " +
                        "Cấu trúc: { \"metric\": \"...\", \"prediction\": \"...\", \"confidence\": 0-100, \"timeframe\": \"...\" }. " +
                        "QUAN TRỌNG: Chỉ trả về JSON thuần túy, không dùng Markdown block. Trả lời bằng tiếng Việt Nam.",
                context
        );
        String jsonResponse = callGeminiAPI(prompt);
        return parseResponse(jsonResponse, new TypeReference<List<DashboardStatsDTO.Prediction>>() {});
    }

    @Override
    public List<DashboardStatsDTO.Recommendation> generateRecommendations(String context) {
        String prompt = String.format(
                "Dựa trên dữ liệu: %s\n" +
                        "Đưa ra 3-5 khuyến nghị cải thiện dưới dạng JSON Array. " +
                        "Cấu trúc: { \"title\": \"...\", \"description\": \"...\", \"action\": \"...\", \"impact\": 1-5 }. " +
                        "QUAN TRỌNG: Chỉ trả về JSON thuần túy, không dùng Markdown block. Trả lời bằng tiếng Việt Nam.",
                context
        );
        String jsonResponse = callGeminiAPI(prompt);
        return parseResponse(jsonResponse, new TypeReference<List<DashboardStatsDTO.Recommendation>>() {});
    }

    private String callGeminiAPI(String prompt) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.warn("Gemini API Key is missing — returning empty response");
            return "[]";
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
            ));

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            String url = GEMINI_API_URL + "?key=" + geminiApiKey;

            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return extractTextFromResponse(response.getBody());
            }
        } catch (Exception e) {
            log.error("Error calling Gemini API: {}", e.getMessage());
        }
        return "[]";
    }

    @SuppressWarnings("unchecked")
    private String extractTextFromResponse(Map<String, Object> responseBody) {
        try {
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBody.get("candidates");
            if (candidates != null && !candidates.isEmpty()) {
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                if (parts != null && !parts.isEmpty()) {
                    return (String) parts.get(0).get("text");
                }
            }
        } catch (Exception e) {
            log.error("Error parsing Gemini response structure", e);
        }
        return "[]";
    }

    private <T> List<T> parseResponse(String jsonResponse, TypeReference<List<T>> typeRef) {
        try {
            String cleaned = cleanJsonString(jsonResponse);
            return objectMapper.readValue(cleaned, typeRef);
        } catch (Exception e) {
            log.error("Failed to parse JSON from Gemini AI: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String cleanJsonString(String json) {
        if (json == null || json.isBlank()) return "[]";
        String cleaned = json.trim();
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
        return cleaned.trim();
    }
}
